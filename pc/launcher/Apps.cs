using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Windows.Media.Imaging;

namespace KidLock.Launcher
{
    /// <summary>Finding, drawing and opening the apps (the everyday tools among them).</summary>
    static class Apps
    {
        const int IconSize = 256;
        static readonly Dictionary<string, BitmapSource> icons = new Dictionary<string, BitmapSource>(StringComparer.OrdinalIgnoreCase);

        /// <summary>
        /// The app's icon, as Start or Explorer shows it: a packaged app's from shell:AppsFolder, a
        /// desktop program's from its exe, or the first letter when there is none. Cached.
        /// </summary>
        public static BitmapSource Icon(PcApp app)
        {
            int color = ColorFor(app.Id);
            if (app.IsPackaged)
                return Cached("aumid:" + app.Aumid, () => Native.ShellIcon(@"shell:AppsFolder\" + app.Aumid, IconSize), app.Label, color);
            var exe = Exe(app);
            return exe == null
                ? Cached("letter:" + app.Id, () => null, app.Label, color)
                : Cached("exe:" + exe, () => Native.ShellIcon(exe, IconSize), app.Label, color);
        }

        /// <summary>A desktop program whose exe isn't on the PC (yet). A packaged app is never "missing".</summary>
        public static bool Missing(PcApp app) => !app.IsPackaged && Exe(app) == null;

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

        /// <summary>Opens an app: a packaged one through Windows, a desktop one by its exe. Throws if it can't.</summary>
        public static void Open(PcApp app)
        {
            if (app.IsPackaged)
            {
                OpenPackaged(app.Aumid, app.Label);
                return;
            }
            var exe = Exe(app, fresh: true) ?? throw new FileNotFoundException("not found", app.Launch);
            Process.Start(new ProcessStartInfo(exe) { UseShellExecute = false, WorkingDirectory = Path.GetDirectoryName(exe) });
        }

        /// <summary>Starts (or brings forward) a Store-style app; falls back to Explorer. Throws if both fail.</summary>
        static void OpenPackaged(string aumid, string label)
        {
            int hr;
            try
            {
                hr = Native.ActivateApp(aumid);
            }
            catch (Exception e)
            {
                hr = e.HResult;
            }
            if (hr == 0) return;
            Log.Write($"{label}: activation failed (0x{hr:X8}); trying Explorer");
            Process.Start(new ProcessStartInfo("explorer.exe", @"shell:AppsFolder\" + aumid) { UseShellExecute = false });
        }
    }
}
