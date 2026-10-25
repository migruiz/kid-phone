using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Windows.Media.Imaging;

namespace KidLock.Launcher
{
    /// <summary>Finding, drawing and opening the tools and apps.</summary>
    static class Apps
    {
        const int IconSize = 256;
        static readonly Dictionary<string, BitmapSource> icons = new Dictionary<string, BitmapSource>(StringComparer.OrdinalIgnoreCase);

        /// <summary>A tool's icon, as Start shows it; cached.</summary>
        public static BitmapSource ToolIcon(Tool tool, int colorIndex) =>
            Cached("tool:" + tool.Aumid, () => Native.ShellIcon(@"shell:AppsFolder\" + tool.Aumid, IconSize), tool.Label, colorIndex);

        /// <summary>An app's icon from its exe (or a letter if the exe isn't there); cached per exe.</summary>
        public static BitmapSource AppIcon(PcApp app, string exe, int colorIndex) =>
            exe == null
                ? Cached("letter:" + app.Id, () => null, app.Label, colorIndex)
                : Cached("exe:" + exe, () => Native.ShellIcon(exe, IconSize), app.Label, colorIndex);

        static BitmapSource Cached(string key, Func<BitmapSource> load, string label, int colorIndex)
        {
            if (icons.TryGetValue(key, out var icon)) return icon;
            icon = load() ?? Theme.LetterIcon(label, colorIndex);
            icons[key] = icon;
            return icon;
        }

        /// <summary>An app's tile colour, the same every time (from its id).</summary>
        public static int ColorFor(string id)
        {
            int h = 0;
            foreach (char c in id ?? "") h = unchecked(h * 31 + c);
            return Math.Abs(h % Theme.TileColors.Length);
        }

        // ---- Where an app's exe is ----

        static readonly Dictionary<string, (string exe, DateTime at)> resolved = new Dictionary<string, (string, DateTime)>();

        /// <summary>The app's exe, looked up again at most every 30 seconds (and always when [fresh]).</summary>
        public static string Exe(PcApp app, bool fresh = false)
        {
            if (string.IsNullOrWhiteSpace(app.Launch)) return null;
            if (!fresh && resolved.TryGetValue(app.Launch, out var known) && DateTime.UtcNow - known.at < TimeSpan.FromSeconds(30)) return known.exe;
            var exe = Resolve(app.Launch);
            resolved[app.Launch] = (exe, DateTime.UtcNow);
            return exe;
        }

        /// <summary>
        /// "%LOCALAPPDATA%\Roblox\Versions\*\RobloxPlayerBeta.exe" → the file in the newest matching
        /// folder that has it (newest by when the folder was made: Roblox makes one per update and
        /// may leave the old one behind). Null if there is none.
        /// </summary>
        public static string Resolve(string pattern)
        {
            var parts = Environment.ExpandEnvironmentVariables(pattern).Split(new[] { '\\', '/' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 2 || !parts[0].EndsWith(":")) return null; // needs a drive: C:\...
            var found = new List<(string path, DateTime newest)> { (parts[0] + "\\", DateTime.MinValue) };
            for (int i = 1; i < parts.Length && found.Count > 0; i++)
            {
                string part = parts[i];
                bool last = i == parts.Length - 1;
                bool wild = part.IndexOfAny(new[] { '*', '?' }) >= 0;
                var next = new List<(string, DateTime)>();
                foreach (var (path, newest) in found)
                {
                    try
                    {
                        if (wild && last)
                            next.AddRange(Directory.GetFiles(path, part).Select(f => (f, newest)));
                        else if (wild)
                            next.AddRange(Directory.GetDirectories(path, part).Select(d => (d, Directory.GetCreationTimeUtc(d))));
                        else
                        {
                            var p = Path.Combine(path, part);
                            if (last ? File.Exists(p) : Directory.Exists(p)) next.Add((p, newest));
                        }
                    }
                    catch (Exception e) when (e is IOException || e is UnauthorizedAccessException || e is ArgumentException)
                    {
                        // a folder he can't look into: not this one
                    }
                }
                found = next;
            }
            return found.OrderByDescending(f => f.newest).Select(f => f.path).FirstOrDefault();
        }

        // ---- Opening ----

        /// <summary>Opens a tool. Throws if Windows refuses.</summary>
        public static void Open(Tool tool)
        {
            int hr;
            try
            {
                hr = Native.ActivateApp(tool.Aumid);
            }
            catch (Exception e)
            {
                hr = e.HResult;
            }
            if (hr == 0) return;
            Log.Write($"{tool.Label}: activation failed (0x{hr:X8}); trying Explorer");
            Process.Start(new ProcessStartInfo("explorer.exe", @"shell:AppsFolder\" + tool.Aumid) { UseShellExecute = false });
        }

        /// <summary>Opens an app by its exe. Throws if it can't be found or started.</summary>
        public static void Open(PcApp app)
        {
            var exe = Exe(app, fresh: true) ?? throw new FileNotFoundException("not found", app.Launch);
            Process.Start(new ProcessStartInfo(exe) { UseShellExecute = false, WorkingDirectory = Path.GetDirectoryName(exe) });
        }
    }
}
