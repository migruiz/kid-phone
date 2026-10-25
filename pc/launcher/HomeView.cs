using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace KidLock.Launcher
{
    /// <summary>
    /// The home screen itself, like the phone's: "Hi Alejandro 👋", the time and the battery, then a
    /// big button for each app he may open now (switched-on apps first, with their minutes left),
    /// then the everyday tools. Used by the window, and by --snapshot to draw it into a picture.
    /// </summary>
    sealed class HomeView : Grid
    {
        const double Gap = 28;

        readonly TextBlock greeting, time, date, note;
        readonly Border battery;
        readonly Grid tileArea;
        /// <summary>The buttons in rows, each row centred (so a short last row sits in the middle).</summary>
        readonly StackPanel rows = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        readonly Dictionary<string, Tile> tiles = new Dictionary<string, Tile>();
        List<string> order = new List<string>();

        /// <summary>The battery (percent or -1 for none, charger in). Snapshots set a made-up one.</summary>
        public Func<(int percent, bool charging)> ReadBattery = Native.Battery;
        /// <summary>The time on screen. Snapshots fix it.</summary>
        public Func<DateTime> Clock = () => DateTime.Now;

        public HomeView(string childName)
        {
            Children.Add(Theme.NightSky());

            var page = new DockPanel { Margin = new Thickness(56, 40, 56, 32), LastChildFill = true };
            Children.Add(page);

            // ---- The top: greeting and time on the left, the battery on the right ----
            var header = new Grid();
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            DockPanel.SetDock(header, Dock.Top);
            page.Children.Add(header);

            var left = new StackPanel();
            header.Children.Add(left);
            greeting = Theme.Text("", 34, FontWeights.ExtraBold, Theme.White(0.92));
            greeting.Inlines.Add(new System.Windows.Documents.Run(string.IsNullOrWhiteSpace(childName) ? "Hi!" : $"Hi {childName} "));
            // WPF draws emoji in one colour: a warm yellow hand, like the phone's 👋.
            greeting.Inlines.Add(new System.Windows.Documents.Run("\U0001F44B")
            {
                FontFamily = new FontFamily("Segoe UI Emoji"),
                Foreground = Theme.Brush(Theme.Yellow),
            });
            left.Children.Add(greeting);

            var clockLine = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 2, 0, 0) };
            time = Theme.Text("", 84, FontWeights.Black);
            time.LineHeight = 92;
            time.LineStackingStrategy = LineStackingStrategy.BlockLineHeight;
            date = Theme.Text("", 24, FontWeights.Bold, Theme.White(0.55));
            date.VerticalAlignment = VerticalAlignment.Bottom;
            date.Margin = new Thickness(22, 0, 0, 18);
            clockLine.Children.Add(time);
            clockLine.Children.Add(date);
            left.Children.Add(clockLine);

            battery = new Border { VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 6, 0, 0) };
            Grid.SetColumn(battery, 1);
            header.Children.Add(battery);

            // ---- The bottom: a quiet note when something is off ----
            note = Theme.Text("", 18, FontWeights.Bold, Theme.White(0.5));
            note.HorizontalAlignment = HorizontalAlignment.Center;
            note.Margin = new Thickness(0, 12, 0, 0);
            DockPanel.SetDock(note, Dock.Bottom);
            page.Children.Add(note);

            // ---- The buttons, as big as fits ----
            tileArea = new Grid { Margin = new Thickness(0, 24, 0, 0) };
            tileArea.Children.Add(rows);
            tileArea.SizeChanged += (s, e) => LayoutTiles();
            page.Children.Add(tileArea);
        }

        /// <summary>Brings the screen up to date. Called every second.</summary>
        public void Update(NowState state, long now)
        {
            bool fresh = state != null && state.IsFresh(now);
            var tools = state != null && state.Tools.Count > 0 ? state.Tools : NowState.DefaultTools.ToList();
            var apps = fresh ? state.Apps.Where(a => a.IsOn(now)).OrderBy(a => a.Label, StringComparer.CurrentCultureIgnoreCase).ToList() : new List<PcApp>();

            // The buttons: apps switched on first, then the tools.
            var wanted = new List<string>();
            foreach (var app in apps)
            {
                string key = "app:" + app.Id;
                wanted.Add(key);
                var exe = Apps.Exe(app);
                int color = Apps.ColorFor(app.Id);
                if (!tiles.TryGetValue(key, out var tile))
                {
                    var a = app;
                    tiles[key] = tile = new Tile(app.Label, color, () => Open(key, a));
                }
                tile.SetIcon(Apps.AppIcon(app, exe, color));
                tile.SetLabel(app.Label);
                tile.SetMinutes(app.EndsAt is long end ? MinutesLeft(end, now) : (long?)null);
                tile.SetMissing(exe == null);
            }
            for (int i = 0; i < tools.Count; i++)
            {
                var tool = tools[i];
                string key = "tool:" + tool.Aumid;
                wanted.Add(key);
                if (!tiles.TryGetValue(key, out var tile))
                {
                    var t = tool;
                    tiles[key] = tile = new Tile(tool.Label, i, () => Open(key, t));
                    tile.SetIcon(Apps.ToolIcon(tool, i));
                }
                tile.SetLabel(tool.Label);
            }
            if (!wanted.SequenceEqual(order))
            {
                order = wanted;
                foreach (var gone in tiles.Keys.Except(order).ToList()) tiles.Remove(gone);
                LayoutTiles();
            }

            note.Text = !fresh ? "Games and extra apps are off right now." : !state.HubOk ? "Not connected to home right now." : "";

            var clock = Clock();
            bool twentyFour = CultureInfo.CurrentCulture.DateTimeFormat.ShortTimePattern.Contains("H");
            time.Text = clock.ToString(twentyFour ? "HH:mm" : "h:mm", CultureInfo.CurrentCulture);
            date.Text = clock.ToString("dddd d MMMM", CultureInfo.CurrentCulture);
            UpdateBattery();
        }

        /// <summary>"45 min": whole minutes, rounded up, at least 1 (as on the phone).</summary>
        public static long MinutesLeft(long endsAt, long now) => Math.Max(1, (endsAt - now + 59_999) / 60_000);

        void Open(string key, object what)
        {
            if (!tiles.TryGetValue(key, out var tile)) return;
            try
            {
                if (what is Tool tool) Apps.Open(tool);
                else if (what is PcApp app) Apps.Open(app);
                Log.Write($"opened {key}");
                tile.Flash("Opening…", TimeSpan.FromSeconds(3));
            }
            catch (Exception e)
            {
                Log.Write($"could not open {key}: {e.Message}");
                tile.Flash(what is PcApp ? "Not found on this PC" : "Didn't open. Try again", TimeSpan.FromSeconds(4));
            }
        }

        /// <summary>As many columns as makes the buttons big and the rows few; centred.</summary>
        void LayoutTiles()
        {
            foreach (StackPanel row in rows.Children) row.Children.Clear();
            rows.Children.Clear();
            int n = order.Count;
            double width = tileArea.ActualWidth, height = tileArea.ActualHeight;
            if (n == 0 || width <= 0 || height <= 0) return;
            int cols = n <= 4 ? n : n <= 10 ? (n + 1) / 2 : 5;
            int rowCount = (n + cols - 1) / cols;
            double w = Math.Min(310, (width - cols * Gap) / cols);
            double h = Math.Min(w * 0.86, (height - rowCount * Gap) / rowCount);
            w = Math.Min(w, h / 0.72); // never flat and wide
            StackPanel current = null;
            for (int i = 0; i < n; i++)
            {
                if (i % cols == 0)
                {
                    current = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
                    rows.Children.Add(current);
                }
                var tile = tiles[order[i]];
                tile.SetSize(w, h, Gap / 2);
                current.Children.Add(tile);
            }
        }

        void UpdateBattery()
        {
            var (percent, charging) = ReadBattery();
            if (percent < 0)
            {
                battery.Visibility = Visibility.Collapsed;
                return;
            }
            battery.Visibility = Visibility.Visible;
            bool low = percent <= 20;
            battery.CornerRadius = new CornerRadius(999);
            battery.Padding = new Thickness(18, 11, 18, 11);
            battery.Background = low ? Theme.Brush(Theme.C(0x40EF4444)) : Theme.White(0.08);
            battery.BorderBrush = low ? Theme.Brush(Theme.C(0x80EF4444)) : Theme.White(0.12);
            battery.BorderThickness = new Thickness(1);
            var fill = low ? Theme.Red : percent <= 50 ? Theme.Amber : Theme.Green;
            var row = new StackPanel { Orientation = Orientation.Horizontal };
            row.Children.Add(BatteryGlyph(percent, fill));
            var text = Theme.Text($"{percent}%", 24, FontWeights.ExtraBold);
            text.Margin = new Thickness(10, 0, 0, 0);
            text.VerticalAlignment = VerticalAlignment.Center;
            row.Children.Add(text);
            if (charging)
            {
                row.Children.Add(new Path
                {
                    // Material "Bolt"
                    Data = Geometry.Parse("M11,21h-1l1,-7H7.5c-0.88,0 -0.33,-0.75 -0.31,-0.78C8.48,10.94 10.42,7.54 13.01,3h1l-1,7h3.51c0.4,0 0.62,0.19 0.4,0.66C12.97,17.55 11,21 11,21z"),
                    Fill = Theme.Brush(Theme.Yellow),
                    Stretch = Stretch.Uniform,
                    Width = 22,
                    Height = 22,
                    Margin = new Thickness(4, 0, 0, 0),
                    VerticalAlignment = VerticalAlignment.Center,
                });
            }
            battery.Child = row;
        }

        /// <summary>The phone's battery drawing: outline, cap, and a fill as long as the charge.</summary>
        static UIElement BatteryGlyph(int percent, Color fill)
        {
            const double width = 46, height = 24, stroke = 2.5, cap = 4.5;
            double body = width - cap - 1;
            var canvas = new Canvas { Width = width, Height = height, VerticalAlignment = VerticalAlignment.Center };
            var outline = Theme.White(0.85);
            var box = new Rectangle { Width = body, Height = height, RadiusX = 6, RadiusY = 6, Stroke = outline, StrokeThickness = stroke };
            canvas.Children.Add(box);
            var nub = new Rectangle { Width = cap, Height = height * 0.36, RadiusX = 2, RadiusY = 2, Fill = outline };
            Canvas.SetLeft(nub, body + 1);
            Canvas.SetTop(nub, height * 0.32);
            canvas.Children.Add(nub);
            double inset = stroke + 2;
            var level = new Rectangle
            {
                Width = Math.Max(0, (body - 2 * inset) * Math.Min(100, percent) / 100.0),
                Height = height - 2 * inset,
                RadiusX = 3,
                RadiusY = 3,
                Fill = Theme.Brush(fill),
            };
            Canvas.SetLeft(level, inset);
            Canvas.SetTop(level, inset);
            canvas.Children.Add(level);
            return canvas;
        }
    }

    /// <summary>
    /// One big button: the icon on a soft plate, the name underneath, and for an app on for a while
    /// the minutes left on its corner (green, amber in the last five). It gives a little when pressed.
    /// </summary>
    sealed class Tile : Grid
    {
        readonly Border card, hover, plate, badge;
        readonly Image image = new Image { Stretch = Stretch.Uniform };
        readonly TextBlock label, status, badgeText;
        readonly ScaleTransform scale = new ScaleTransform(1, 1);
        readonly Action open;
        readonly DispatcherTimer flashTimer = new DispatcherTimer();
        bool pressed, missing;
        string flash;

        public Tile(string text, int colorIndex, Action open)
        {
            this.open = open;
            var (light, _) = Theme.TileColor(colorIndex);
            Cursor = Cursors.Hand;
            RenderTransformOrigin = new Point(0.5, 0.5);
            RenderTransform = scale;
            SnapsToDevicePixels = true;

            card = new Border
            {
                CornerRadius = new CornerRadius(34),
                Background = new LinearGradientBrush(light.WithAlpha(0.42), Colors.White.WithAlpha(0.05), 90),
                BorderBrush = Theme.White(0.11),
                BorderThickness = new Thickness(1.5),
            };
            Children.Add(card);
            hover = new Border { CornerRadius = new CornerRadius(34), Background = Theme.White(1), Opacity = 0, IsHitTestVisible = false };
            Children.Add(hover);

            var stack = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            plate = new Border
            {
                Background = Theme.White(0.08),
                BorderBrush = Theme.White(0.10),
                BorderThickness = new Thickness(1),
                HorizontalAlignment = HorizontalAlignment.Center,
                Child = image,
            };
            image.Effect = new DropShadowEffect { BlurRadius = 18, ShadowDepth = 3, Direction = 270, Opacity = 0.35, Color = Colors.Black };
            stack.Children.Add(plate);
            label = Theme.Text(text, 26, FontWeights.ExtraBold);
            label.HorizontalAlignment = HorizontalAlignment.Center;
            label.TextAlignment = TextAlignment.Center;
            label.TextTrimming = TextTrimming.CharacterEllipsis;
            label.Margin = new Thickness(12, 14, 12, 0);
            stack.Children.Add(label);
            status = Theme.Text("", 16, FontWeights.Bold, Theme.White(0.7));
            status.HorizontalAlignment = HorizontalAlignment.Center;
            status.Visibility = Visibility.Collapsed;
            stack.Children.Add(status);
            Children.Add(stack);

            badgeText = Theme.Text("", 20, FontWeights.Black);
            badge = new Border
            {
                CornerRadius = new CornerRadius(999),
                Padding = new Thickness(14, 4, 14, 5),
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(0, 16, 16, 0),
                Child = badgeText,
                Visibility = Visibility.Collapsed,
                Effect = new DropShadowEffect { BlurRadius = 12, ShadowDepth = 2, Direction = 270, Opacity = 0.3 },
            };
            Children.Add(badge);

            MouseEnter += (s, e) => Fade(hover, 0.06);
            MouseLeave += (s, e) => { Fade(hover, 0); if (pressed) Release(false); };
            MouseLeftButtonDown += (s, e) =>
            {
                pressed = true;
                CaptureMouse();
                Squeeze(0.94, 90, null);
                e.Handled = true;
            };
            MouseLeftButtonUp += (s, e) => { if (pressed) Release(IsMouseOver); e.Handled = true; };

            flashTimer.Tick += (s, e) => { flashTimer.Stop(); flash = null; ShowStatus(); };
        }

        void Release(bool activate)
        {
            pressed = false;
            ReleaseMouseCapture();
            Squeeze(1, 380, new ElasticEase { Oscillations = 1, Springiness = 6 });
            if (activate) open();
        }

        void Squeeze(double to, int ms, IEasingFunction easing)
        {
            var animation = new DoubleAnimation(to, TimeSpan.FromMilliseconds(ms)) { EasingFunction = easing };
            scale.BeginAnimation(ScaleTransform.ScaleXProperty, animation);
            scale.BeginAnimation(ScaleTransform.ScaleYProperty, animation);
        }

        static void Fade(UIElement element, double to) =>
            element.BeginAnimation(OpacityProperty, new DoubleAnimation(to, TimeSpan.FromMilliseconds(150)));

        public void SetSize(double w, double h, double margin)
        {
            Width = w;
            Height = h;
            Margin = new Thickness(margin);
            double plateSize = Math.Round(h * 0.5);
            plate.Width = plate.Height = plateSize;
            plate.CornerRadius = new CornerRadius(plateSize * 0.3);
            image.Width = image.Height = Math.Round(plateSize * 0.72);
            label.FontSize = Math.Max(18, Math.Min(28, h * 0.1));
            label.MaxWidth = w - 24;
        }

        public void SetIcon(BitmapSource icon)
        {
            if (!ReferenceEquals(image.Source, icon)) image.Source = icon;
        }

        public void SetLabel(string text)
        {
            if (label.Text != text) label.Text = text;
        }

        public void SetMinutes(long? minutes)
        {
            if (minutes == null)
            {
                badge.Visibility = Visibility.Collapsed;
                return;
            }
            badge.Visibility = Visibility.Visible;
            badgeText.Text = $"{minutes} min";
            badge.Background = Theme.Brush(minutes <= 5 ? Theme.Amber : Theme.Green);
        }

        /// <summary>The app's exe isn't on the PC (yet): dimmed, with a note.</summary>
        public void SetMissing(bool isMissing)
        {
            if (missing == isMissing) return;
            missing = isMissing;
            Opacity = missing ? 0.55 : 1;
            ShowStatus();
        }

        /// <summary>A note under the name for a few seconds ("Opening…").</summary>
        public void Flash(string text, TimeSpan duration)
        {
            flash = text;
            ShowStatus();
            flashTimer.Stop();
            flashTimer.Interval = duration;
            flashTimer.Start();
        }

        void ShowStatus()
        {
            var text = flash ?? (missing ? "Not found on this PC" : null);
            status.Text = text ?? "";
            status.Visibility = text == null ? Visibility.Collapsed : Visibility.Visible;
        }
    }
}
