using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Web.Script.Serialization;
using System.Windows.Threading;

namespace KidLock.Launcher
{
    /// <summary>A Windows tool he may always open (a Store-style app, by its app ID).</summary>
    sealed class Tool
    {
        public string Label;
        public string Aumid;
    }

    /// <summary>An app the parents switch on and off from Kid Phone Parent (Roblox, …).</summary>
    sealed class PcApp
    {
        public string Id;
        public string Label;
        /// <summary>The exe, with %VARS% and * for "the newest folder that has it".</summary>
        public string Launch;
        public bool Allowed;
        /// <summary>When its time is up (epoch ms, this PC's clock), or null for no time limit.</summary>
        public long? EndsAt;
        public int? Minutes;

        /// <summary>On right now: allowed, and its time (if any) not over.</summary>
        public bool IsOn(long now) => Allowed && (EndsAt == null || EndsAt > now);
    }

    /// <summary>
    /// now.json, written by the KidLock agent (SYSTEM) whenever anything changes and at least every
    /// minute. He can read it but not change it.
    /// </summary>
    sealed class NowState
    {
        public const string DefaultPath = @"C:\ProgramData\KidLockLayout\now.json";
        /// <summary>Older than this, the agent isn't running: only the tools are offered.</summary>
        public const long StaleAfterMs = 2 * 60_000;

        public long WrittenAt;
        public bool HubOk;
        public List<Tool> Tools = new List<Tool>();
        public List<PcApp> Apps = new List<PcApp>();

        /// <summary>The everyday Windows tools, used when now.json is missing or has none.</summary>
        public static readonly Tool[] DefaultTools =
        {
            new Tool { Label = "Calculator", Aumid = "Microsoft.WindowsCalculator_8wekyb3d8bbwe!App" },
            new Tool { Label = "Notepad", Aumid = "Microsoft.WindowsNotepad_8wekyb3d8bbwe!App" },
            new Tool { Label = "Paint", Aumid = "Microsoft.Paint_8wekyb3d8bbwe!App" },
            new Tool { Label = "Photos", Aumid = "Microsoft.Windows.Photos_8wekyb3d8bbwe!App" },
            new Tool { Label = "Camera", Aumid = "Microsoft.WindowsCamera_8wekyb3d8bbwe!App" },
            new Tool { Label = "Snipping Tool", Aumid = "Microsoft.ScreenSketch_8wekyb3d8bbwe!App" },
        };

        public static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

        public bool IsFresh(long now) => WrittenAt > 0 && Math.Abs(now - WrittenAt) < StaleAfterMs;

        /// <summary>Reads now.json. Throws on a missing file or bad JSON (the caller keeps the last good state).</summary>
        public static NowState Read(string path)
        {
            string text;
            // ReadWrite | Delete: the agent can replace the file (rename over it) while we read.
            using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            using (var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: true))
            {
                text = reader.ReadToEnd();
            }
            return Parse(text);
        }

        public static NowState Parse(string text)
        {
            if (!(new JavaScriptSerializer().DeserializeObject(text) is Dictionary<string, object> root))
                throw new FormatException("now.json is not a JSON object");
            var state = new NowState
            {
                WrittenAt = Long(root, "writtenAt") ?? 0,
                HubOk = Bool(root, "hubOk") ?? false,
            };
            foreach (var t in Objects(root, "tools"))
            {
                var tool = new Tool { Label = Str(t, "label"), Aumid = Str(t, "aumid") };
                if (!string.IsNullOrWhiteSpace(tool.Aumid)) state.Tools.Add(tool);
            }
            foreach (var a in Objects(root, "apps"))
            {
                var app = new PcApp
                {
                    Id = Str(a, "id"),
                    Label = Str(a, "label"),
                    Launch = Str(a, "launch"),
                    Allowed = Bool(a, "allowed") ?? false,
                    EndsAt = Long(a, "endsAt"),
                    Minutes = (int?)Long(a, "minutes"),
                };
                if (string.IsNullOrWhiteSpace(app.Id)) continue;
                if (string.IsNullOrWhiteSpace(app.Label)) app.Label = app.Id;
                state.Apps.Add(app);
            }
            return state;
        }

        static IEnumerable<Dictionary<string, object>> Objects(Dictionary<string, object> o, string key) =>
            o.TryGetValue(key, out var v) && v is object[] list ? list.OfType<Dictionary<string, object>>() : Enumerable.Empty<Dictionary<string, object>>();

        static string Str(Dictionary<string, object> o, string key) => o.TryGetValue(key, out var v) ? v as string : null;

        static bool? Bool(Dictionary<string, object> o, string key) => o.TryGetValue(key, out var v) && v is bool b ? b : (bool?)null;

        static long? Long(Dictionary<string, object> o, string key)
        {
            if (!o.TryGetValue(key, out var v) || v == null) return null;
            switch (v)
            {
                case int i: return i;
                case long l: return l;
                case decimal d: return (long)d;
                case double f: return (long)f;
                default: return null;
            }
        }
    }

    /// <summary>
    /// Reads now.json every second and keeps the last good copy: a half-written or broken file
    /// never empties the screen.
    /// </summary>
    sealed class StateSource
    {
        readonly string path;
        readonly DispatcherTimer timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        string lastError;

        public StateSource(string path)
        {
            this.path = path;
            timer.Tick += (s, e) => Refresh();
        }

        /// <summary>The last good now.json, or null if none was ever read.</summary>
        public NowState Last { get; private set; }

        /// <summary>Every second, after reading the file again.</summary>
        public event Action Changed;

        /// <summary>Labels of every app seen so far, for a warning about an app that has just gone.</summary>
        public readonly Dictionary<string, PcApp> Seen = new Dictionary<string, PcApp>();

        public void Start()
        {
            Refresh();
            timer.Start();
        }

        public void Refresh()
        {
            try
            {
                Last = NowState.Read(path);
                foreach (var app in Last.Apps) Seen[app.Id] = app;
                if (lastError != null) Log.Write("now.json readable again");
                lastError = null;
            }
            catch (Exception e)
            {
                var error = e is FileNotFoundException || e is DirectoryNotFoundException ? "now.json is missing" : "now.json: " + e.Message;
                if (error != lastError) Log.Write(error); // once, not every second
                lastError = error;
            }
            Changed?.Invoke();
        }
    }
}
