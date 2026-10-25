using System;
using System.Collections.Generic;
using System.IO;
using System.Media;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;

namespace KidLock.Launcher
{
    /// <summary>
    /// Tells him how long is left of an app the parents switched on for a while, over whatever is on
    /// screen (a game included), without getting in its way: clicks go through the card to the game.
    /// The KidLock agent closes the app when the time is up; this only warns. See WarningRules for when.
    /// </summary>
    sealed class TimeWarnings
    {
        const string ChimeFile = @"C:\Windows\Media\Windows Notify System Generic.wav";

        readonly StateSource source;
        readonly WarningRules rules = new WarningRules();
        readonly DispatcherTimer timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(200) };
        readonly WarningCard card = new WarningCard();
        OverlayWindow overlay;
        bool locked;
        int ticks;
        SoundPlayer chime;

        public TimeWarnings(StateSource source)
        {
            this.source = source;
            timer.Tick += (s, e) => Tick();
            SystemEvents.SessionSwitch += (s, e) =>
            {
                if (e.Reason == SessionSwitchReason.SessionLock || e.Reason == SessionSwitchReason.ConsoleDisconnect) locked = true;
                if (e.Reason == SessionSwitchReason.SessionUnlock || e.Reason == SessionSwitchReason.ConsoleConnect) locked = false;
            };
        }

        public void Start() => timer.Start();

        void Tick()
        {
            long now = NowState.Now();
            var ends = new Dictionary<string, long>();
            var state = source.Last; // the last good one: warnings carry on if the agent is a little late
            if (state != null)
            {
                foreach (var app in state.Apps)
                    if (app.Allowed && app.EndsAt is long end && end > now) ends[app.Id] = end;
            }
            foreach (var action in rules.Step(now, ends, !locked)) Apply(action);

            // Games put themselves on top now and then: back on top once a second while showing.
            if (overlay != null && overlay.IsVisible && ++ticks % 5 == 0) overlay.KeepOnTop();
        }

        void Apply(WarningAction action)
        {
            string label = Label(action.Id);
            switch (action.Kind)
            {
                case WarningKind.Remind:
                    Log.Write($"{label}: {action.Minutes} minutes left");
                    card.ShowReminder(action.Minutes, label, Icon(action.Id));
                    PlayChime();
                    Show();
                    break;
                case WarningKind.CountdownStart:
                    Log.Write($"{label}: last {action.Seconds} seconds");
                    card.ShowCountdown(label, action.Seconds, Icon(action.Id));
                    if (action.Chime) PlayChime();
                    Show();
                    break;
                case WarningKind.CountdownTick:
                    card.Tick(action.Seconds);
                    break;
                case WarningKind.TimesUp:
                    Log.Write($"{label}: time's up");
                    card.ShowTimesUp(label, Icon(action.Id));
                    break;
                case WarningKind.Hide:
                    overlay?.Hide();
                    break;
            }
        }

        void Show()
        {
            if (overlay == null) overlay = new OverlayWindow(card);
            if (!overlay.IsVisible) overlay.Show();
            overlay.KeepOnTop();
        }

        string Label(string id) => id != null && source.Seen.TryGetValue(id, out var app) ? app.Label : id ?? "";

        BitmapSource Icon(string id) =>
            id != null && source.Seen.TryGetValue(id, out var app) ? Apps.AppIcon(app, Apps.Exe(app), Apps.ColorFor(app.Id)) : null;

        /// <summary>Windows' notification sound, once, so a child busy with a game notices.</summary>
        void PlayChime()
        {
            try
            {
                if (chime == null && File.Exists(ChimeFile))
                {
                    chime = new SoundPlayer(ChimeFile);
                    chime.Load();
                }
                if (chime != null) chime.Play();
                else SystemSounds.Asterisk.Play();
            }
            catch
            {
                SystemSounds.Asterisk.Play();
            }
        }
    }

    /// <summary>
    /// A window for the card at the top centre of the main screen: always on top, see-through to
    /// clicks, never takes the focus, not in the taskbar or Alt+Tab.
    /// </summary>
    sealed class OverlayWindow : Window
    {
        IntPtr handle;

        public OverlayWindow(UIElement content)
        {
            Title = "Kid Phone time left";
            WindowStyle = WindowStyle.None;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            Topmost = true;
            ShowActivated = false;
            ShowInTaskbar = false;
            ResizeMode = ResizeMode.NoResize;
            SizeToContent = SizeToContent.WidthAndHeight;
            Focusable = false;
            IsHitTestVisible = false;
            Content = content;
            SourceInitialized += (s, e) =>
            {
                handle = new WindowInteropHelper(this).Handle;
                int style = Native.GetWindowLong(handle, Native.GWL_EXSTYLE);
                Native.SetWindowLong(handle, Native.GWL_EXSTYLE,
                    style | Native.WS_EX_LAYERED | Native.WS_EX_TRANSPARENT | Native.WS_EX_NOACTIVATE | Native.WS_EX_TOOLWINDOW);
            };
            SizeChanged += (s, e) => Place();
        }

        void Place()
        {
            Left = Math.Round((SystemParameters.PrimaryScreenWidth - ActualWidth) / 2);
            Top = 36;
        }

        public void KeepOnTop()
        {
            if (handle != IntPtr.Zero) Native.KeepOnTop(handle);
        }
    }

    /// <summary>
    /// The phone's warning card: night blue, rounded, a thin yellow edge, the app's icon at the start.
    /// "⏰ 10 minutes left" over the app's name; or "Roblox ends in" over a big number that pops each
    /// second and turns red for the last ten; or "Time's up!" on red.
    /// </summary>
    sealed class WarningCard : Border
    {
        static readonly Color Night = Theme.C(0xF2161C36);
        static readonly Color RedCard = Theme.C(0xF2D92D20);

        readonly Image icon = new Image { Stretch = Stretch.Uniform };
        readonly TextBlock top = Theme.Text("", 22, FontWeights.ExtraBold);
        readonly TextBlock bottom = Theme.Text("", 22, FontWeights.Bold);
        readonly ScaleTransform pop = new ScaleTransform(1, 1);

        public WarningCard()
        {
            CornerRadius = new CornerRadius(38);
            BorderBrush = Theme.Brush(Theme.C(0x80FFC83D));
            BorderThickness = new Thickness(2);
            Padding = new Thickness(22, 18, 44, 18);
            MinWidth = 360;
            Margin = new Thickness(30); // room for the shadow
            Effect = new DropShadowEffect { BlurRadius = 32, ShadowDepth = 6, Direction = 270, Opacity = 0.45 };

            var row = new StackPanel { Orientation = Orientation.Horizontal };
            icon.VerticalAlignment = VerticalAlignment.Center;
            row.Children.Add(icon);
            var column = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(20, 0, 0, 0) };
            column.Children.Add(top);
            column.Children.Add(bottom);
            bottom.RenderTransform = pop;
            bottom.RenderTransformOrigin = new Point(0, 0.5); // grows to the right, into the card
            row.Children.Add(column);
            Child = row;
            Background = Theme.Brush(Night);
        }

        public void ShowReminder(int minutes, string label, BitmapSource appIcon)
        {
            SetIcon(appIcon, 64);
            Background = Theme.Brush(Night);
            top.Inlines.Clear();
            top.Inlines.Add(Alarm());
            top.Inlines.Add(new Run($" {minutes} minutes left"));
            SetLook(top, 34, FontWeights.Black, 1);
            bottom.Text = label;
            SetLook(bottom, 22, FontWeights.Bold, 0.8);
            pop.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            pop.BeginAnimation(ScaleTransform.ScaleYProperty, null);
        }

        public void ShowCountdown(string label, int seconds, BitmapSource appIcon)
        {
            SetIcon(appIcon, 60);
            top.Inlines.Clear();
            top.Text = $"{label} ends in";
            SetLook(top, 22, FontWeights.ExtraBold, 0.85);
            SetLook(bottom, 64, FontWeights.Black, 1);
            Tick(seconds);
        }

        /// <summary>The next number: red for the last ten, and it pops a little.</summary>
        public void Tick(int seconds)
        {
            bottom.Text = seconds.ToString();
            Background = Theme.Brush(seconds <= 10 ? RedCard : Night);
            var grow = new DoubleAnimation(1.35, 1, TimeSpan.FromMilliseconds(250)) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut } };
            pop.BeginAnimation(ScaleTransform.ScaleXProperty, grow);
            pop.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
        }

        public void ShowTimesUp(string label, BitmapSource appIcon)
        {
            SetIcon(appIcon, 60);
            top.Inlines.Clear();
            top.Text = label;
            SetLook(top, 22, FontWeights.ExtraBold, 0.85);
            bottom.Text = "Time's up!";
            SetLook(bottom, 56, FontWeights.Black, 1);
            Background = Theme.Brush(RedCard);
            pop.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            pop.BeginAnimation(ScaleTransform.ScaleYProperty, null);
        }

        /// <summary>Stops the number's pop at its final size (for --snapshot).</summary>
        public void Settle()
        {
            pop.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            pop.BeginAnimation(ScaleTransform.ScaleYProperty, null);
        }

        void SetIcon(BitmapSource source, double size)
        {
            icon.Source = source;
            icon.Width = icon.Height = size;
            icon.Visibility = source == null ? Visibility.Collapsed : Visibility.Visible;
        }

        static void SetLook(TextBlock t, double size, FontWeight weight, double opacity)
        {
            t.FontSize = size;
            t.FontWeight = weight;
            t.Opacity = opacity;
        }

        /// <summary>⏰ in yellow (WPF draws emoji in one colour).</summary>
        static Run Alarm() => new Run("\u23F0") { FontFamily = new FontFamily("Segoe UI Emoji"), Foreground = Theme.Brush(Theme.Yellow) };
    }
}
