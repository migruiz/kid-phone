using System;
using System.Collections.Generic;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace KidLock.Launcher
{
    /// <summary>
    /// --snapshot &lt;dir&gt; [--state &lt;file&gt;]: draws the home screen and the warning cards into PNG
    /// files without opening any window, to check the look on any PC.
    /// </summary>
    static class Snapshot
    {
        public static int Run(Options options)
        {
            Directory.CreateDirectory(options.SnapshotDir);
            long now = NowState.Now();
            bool given = !string.Equals(options.StatePath, NowState.DefaultPath, StringComparison.OrdinalIgnoreCase) || File.Exists(options.StatePath);

            foreach (var face in Fonts.GetTypefaces(new Uri("pack://application:,,,/Fonts/")))
                Console.WriteLine($"font: {string.Join("/", face.FontFamily.FamilyNames.Values)} {face.Weight}");

            NowState state;
            try
            {
                state = given ? NowState.Read(options.StatePath) : Sample(now, Roblox(now, @"%LOCALAPPDATA%\Roblox\Versions\*\RobloxPlayerBeta.exe"));
            }
            catch (Exception e)
            {
                Console.WriteLine($"can't read {options.StatePath}: {e.Message}");
                return 2;
            }
            Home(state, now, Path.Combine(options.SnapshotDir, "home.png"));
            if (!given)
            {
                // Roblox isn't on every PC: Edge stands in, to see a real exe icon on the button.
                Home(Sample(now, Roblox(now, @"%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe")), now, Path.Combine(options.SnapshotDir, "home-standin.png"));
                var stale = Sample(now, Roblox(now, null));
                stale.WrittenAt = now - 10 * 60_000;
                Home(stale, now, Path.Combine(options.SnapshotDir, "home-stale.png"));
            }

            var icon = Theme.LetterIcon("Roblox", Apps.ColorFor("roblox"));
            var edge = Native.ShellIcon(Environment.ExpandEnvironmentVariables(@"%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"), 256) ?? icon;
            Card(c => c.ShowReminder(10, "Roblox", edge), "warn-10min.png", options.SnapshotDir);
            Card(c => c.ShowReminder(5, "Roblox", icon), "warn-5min.png", options.SnapshotDir);
            Card(c => c.ShowCountdown("Roblox", 30, edge), "countdown-30.png", options.SnapshotDir);
            Card(c => c.ShowCountdown("Roblox", 8, edge), "countdown-8.png", options.SnapshotDir);
            Card(c => c.ShowTimesUp("Roblox", edge), "times-up.png", options.SnapshotDir);
            Console.WriteLine("snapshots in " + Path.GetFullPath(options.SnapshotDir));
            return 0;
        }

        static PcApp Roblox(long now, string launch) => new PcApp
        {
            Id = "roblox",
            Label = "Roblox",
            Launch = launch,
            Allowed = true,
            EndsAt = now + 45 * 60_000 - 5_000,
            Minutes = 60,
        };

        static NowState Sample(long now, PcApp app)
        {
            var state = new NowState { WrittenAt = now, HubOk = true, Tools = new List<Tool>(NowState.DefaultTools) };
            state.Apps.Add(app);
            return state;
        }

        static void Home(NowState state, long now, string file)
        {
            var view = new HomeView("Alejandro")
            {
                ReadBattery = () => (78, true),
                Clock = () => new DateTime(2026, 10, 25, 16, 20, 0),
            };
            view.Update(state, now);
            Save(view, 1440, 900, file);
        }

        /// <summary>A card at the top of a made-up game screen.</summary>
        static void Card(Action<WarningCard> setUp, string name, string dir)
        {
            var card = new WarningCard { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Top };
            setUp(card);
            card.Settle();
            var game = new Grid
            {
                Background = new LinearGradientBrush(Theme.C(0xFF4FA3E0), Theme.C(0xFF3C8D3F), 90),
            };
            game.Children.Add(card);
            Save(game, 1000, 280, Path.Combine(dir, name));
        }

        static void Save(FrameworkElement element, int width, int height, string file)
        {
            for (int i = 0; i < 3; i++) // the buttons size themselves once the area's size is known
            {
                element.Measure(new Size(width, height));
                element.Arrange(new Rect(0, 0, width, height));
                element.UpdateLayout();
            }
            var bitmap = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(element);
            var png = new PngBitmapEncoder();
            png.Frames.Add(BitmapFrame.Create(bitmap));
            using (var stream = File.Create(file)) png.Save(stream);
            Console.WriteLine("wrote " + file);
        }
    }
}
