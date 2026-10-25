using System;
using System.ComponentModel;
using System.Globalization;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using Microsoft.Win32;

namespace KidLock.Launcher
{
    /// <summary>
    /// The home screen's window: no frame, covering the main screen (taskbar too while it is in
    /// front), never on top of the apps he opens, so it is there again when they close. It can't be
    /// closed (Alt+F4 does nothing) except by signing out or shutting down.
    /// </summary>
    sealed class HomeWindow : Window
    {
        readonly StateSource source;
        readonly HomeView view;
        readonly bool windowed;

        /// <summary>Set when Windows is signing out or shutting down, or in --window mode.</summary>
        public bool AllowClose;

        public HomeWindow(StateSource source, bool windowed)
        {
            this.source = source;
            this.windowed = windowed;
            Title = "Home";
            Background = new SolidColorBrush(Theme.NightBottom);
            view = new HomeView(DisplayName(Environment.UserName));
            Content = view;

            if (windowed)
            {
                // For trying it out: an ordinary window that closes.
                Width = 1280;
                Height = 800;
                WindowStartupLocation = WindowStartupLocation.CenterScreen;
                AllowClose = true;
            }
            else
            {
                WindowStyle = WindowStyle.None;
                ResizeMode = ResizeMode.NoResize;
                WindowStartupLocation = WindowStartupLocation.Manual;
                FitToMainScreen();
                SystemEvents.DisplaySettingsChanged += (s, e) => Dispatcher.BeginInvoke(new Action(FitToMainScreen));
            }

            source.Changed += Refresh;
            Loaded += (s, e) => Refresh();
        }

        void Refresh()
        {
            view.Update(source.Last, NowState.Now());
            if (!windowed) ComeBackFromTheDesktop();
        }

        protected override void OnClosing(CancelEventArgs e)
        {
            if (!AllowClose)
            {
                e.Cancel = true;
                Log.Write("close refused (only signing out closes the home screen)");
            }
            base.OnClosing(e);
        }

        void FitToMainScreen()
        {
            WindowState = WindowState.Normal;
            Left = 0;
            Top = 0;
            Width = SystemParameters.PrimaryScreenWidth;
            Height = SystemParameters.PrimaryScreenHeight;
        }

        /// <summary>
        /// Like the phone's Home button: if he gets to the bare desktop (Win+D, or the home screen
        /// minimised), the home screen comes back.
        /// </summary>
        void ComeBackFromTheDesktop()
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            if (hwnd == IntPtr.Zero) return;
            var front = Native.ClassOf(Native.GetForegroundWindow());
            if (front != "Progman" && front != "WorkerW") return;
            if (WindowState == WindowState.Minimized) Native.ShowWindow(hwnd, Native.SW_RESTORE);
            Native.SetForegroundWindow(hwnd);
        }

        /// <summary>"alejandro" → "Alejandro".</summary>
        static string DisplayName(string user) =>
            string.IsNullOrWhiteSpace(user) ? "" : CultureInfo.CurrentCulture.TextInfo.ToTitleCase(user.Trim().ToLowerInvariant());
    }
}
