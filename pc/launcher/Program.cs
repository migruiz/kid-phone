// Kid Phone's home screen for the child's Windows account: big buttons for what he may open, and
// the "10 minutes left" / "5 minutes left" / countdown warnings over whatever is on screen.
// What is allowed comes from now.json, written by the KidLock agent (SYSTEM); see README.md.

using System;
using System.Threading;
using System.Windows;

namespace KidLock.Launcher
{
    sealed class Options
    {
        public string StatePath = NowState.DefaultPath;
        public bool Windowed;
        public string SnapshotDir;
        public bool SelfTest;

        public static Options Parse(string[] args)
        {
            var o = new Options();
            for (int i = 0; i < args.Length; i++)
            {
                switch (args[i].ToLowerInvariant())
                {
                    case "--state" when i + 1 < args.Length: o.StatePath = args[++i]; break;
                    case "--window": o.Windowed = true; break;
                    case "--snapshot" when i + 1 < args.Length: o.SnapshotDir = args[++i]; break;
                    case "--selftest": o.SelfTest = true; break;
                }
            }
            return o;
        }
    }

    static class Program
    {
        [STAThread]
        static int Main(string[] args)
        {
            var options = Options.Parse(args);
            if (options.SelfTest) return SelfTest.Run();

            // Created first in every mode: the embedded fonts are found through it.
            var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };

            if (options.SnapshotDir != null) return Snapshot.Run(options);

            Log.Start();
            // One home screen per signed-in user ("Local\" = this session only).
            using (var mutex = new Mutex(true, @"Local\KidLock.Launcher", out bool first))
            {
                if (!first)
                {
                    Log.Write("already running in this session; leaving");
                    return 0;
                }

                // Never crash: a home screen that vanishes leaves him at an empty desktop.
                app.DispatcherUnhandledException += (s, e) =>
                {
                    Log.Write("error: " + e.Exception);
                    e.Handled = true;
                };
                AppDomain.CurrentDomain.UnhandledException += (s, e) => Log.Write("fatal: " + e.ExceptionObject);

                var source = new StateSource(options.StatePath);
                var home = new HomeWindow(source, options.Windowed);
                var warnings = new TimeWarnings(source);
                app.SessionEnding += (s, e) =>
                {
                    Log.Write("Windows is signing out or shutting down");
                    home.AllowClose = true;
                    app.Shutdown();
                };
                if (options.Windowed) home.Closed += (s, e) => app.Shutdown();

                source.Start();
                warnings.Start();
                home.Show();
                Log.Write($"started for {Environment.UserName}, reading {options.StatePath}");
                app.Run();
                GC.KeepAlive(mutex);
                GC.KeepAlive(warnings);
            }
            return 0;
        }
    }
}
