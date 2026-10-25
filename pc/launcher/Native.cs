using System;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace KidLock.Launcher
{
    /// <summary>The bits of Windows that WPF doesn't cover: shell icons, starting Store apps, the battery, window styles.</summary>
    static class Native
    {
        // ---- Icons: what Explorer and Start show for an app or a file ----

        [ComImport, Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IShellItemImageFactory
        {
            [PreserveSig]
            int GetImage(SIZE size, int flags, out IntPtr bitmap);
        }

        [StructLayout(LayoutKind.Sequential)]
        struct SIZE
        {
            public int cx, cy;
        }

        [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
        static extern void SHCreateItemFromParsingName(string path, IntPtr bindContext, [In] ref Guid riid,
            [MarshalAs(UnmanagedType.Interface)] out IShellItemImageFactory item);

        const int SIIGBF_BIGGERSIZEOK = 0x01, SIIGBF_ICONONLY = 0x04;

        /// <summary>
        /// The icon of "shell:AppsFolder\&lt;app ID&gt;" or of a file, about [size] pixels, or null.
        /// </summary>
        public static BitmapSource ShellIcon(string parsingName, int size)
        {
            IntPtr hbm = IntPtr.Zero;
            try
            {
                var iid = typeof(IShellItemImageFactory).GUID;
                SHCreateItemFromParsingName(parsingName, IntPtr.Zero, ref iid, out var factory);
                if (factory.GetImage(new SIZE { cx = size, cy = size }, SIIGBF_ICONONLY | SIIGBF_BIGGERSIZEOK, out hbm) != 0) return null;
                return FromHBitmap(hbm);
            }
            catch
            {
                return null;
            }
            finally
            {
                if (hbm != IntPtr.Zero) DeleteObject(hbm);
            }
        }

        [StructLayout(LayoutKind.Sequential)]
        struct BITMAP
        {
            public int bmType, bmWidth, bmHeight, bmWidthBytes;
            public ushort bmPlanes, bmBitsPixel;
            public IntPtr bmBits;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct BITMAPINFOHEADER
        {
            public int biSize, biWidth, biHeight;
            public ushort biPlanes, biBitCount;
            public int biCompression, biSizeImage, biXPelsPerMeter, biYPelsPerMeter, biClrUsed, biClrImportant;
        }

        [DllImport("gdi32.dll")] static extern int GetObject(IntPtr h, int size, out BITMAP bitmap);
        [DllImport("gdi32.dll")] static extern int GetDIBits(IntPtr hdc, IntPtr hbm, uint start, uint lines, [Out] byte[] bits, ref BITMAPINFOHEADER bmi, uint usage);
        [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr h);
        [DllImport("user32.dll")] static extern IntPtr GetDC(IntPtr hwnd);
        [DllImport("user32.dll")] static extern int ReleaseDC(IntPtr hwnd, IntPtr hdc);

        /// <summary>
        /// A 32-bit shell bitmap as a WPF image, keeping its transparency (Imaging.CreateBitmapSourceFromHBitmap
        /// loses it). The shell's bitmaps are premultiplied; one with no alpha at all is made opaque.
        /// </summary>
        static BitmapSource FromHBitmap(IntPtr hbm)
        {
            if (GetObject(hbm, Marshal.SizeOf(typeof(BITMAP)), out var bm) == 0 || bm.bmWidth <= 0 || bm.bmHeight <= 0) return null;
            int w = bm.bmWidth, h = bm.bmHeight;
            var header = new BITMAPINFOHEADER
            {
                biSize = Marshal.SizeOf(typeof(BITMAPINFOHEADER)),
                biWidth = w,
                biHeight = -h, // top-down
                biPlanes = 1,
                biBitCount = 32,
            };
            var pixels = new byte[w * h * 4];
            var hdc = GetDC(IntPtr.Zero);
            try
            {
                if (GetDIBits(hdc, hbm, 0, (uint)h, pixels, ref header, 0) == 0) return null;
            }
            finally
            {
                ReleaseDC(IntPtr.Zero, hdc);
            }
            bool anyAlpha = false;
            for (int i = 3; i < pixels.Length; i += 4) if (pixels[i] != 0) { anyAlpha = true; break; }
            if (!anyAlpha) for (int i = 3; i < pixels.Length; i += 4) pixels[i] = 255;
            var source = BitmapSource.Create(w, h, 96, 96, PixelFormats.Pbgra32, null, pixels, w * 4);
            source.Freeze();
            return source;
        }

        // ---- Starting a Store-style app (Calculator, Paint, …) ----

        [ComImport, Guid("2e941141-7f97-4756-ba1d-9decde894a3d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IApplicationActivationManager
        {
            [PreserveSig]
            int ActivateApplication([MarshalAs(UnmanagedType.LPWStr)] string appUserModelId,
                [MarshalAs(UnmanagedType.LPWStr)] string arguments, int options, out uint processId);
        }

        [ComImport, Guid("45BA127D-10A8-46EA-8AB7-56EA9078943C")]
        class ApplicationActivationManager { }

        /// <summary>Starts (or brings forward) a Store-style app. Returns 0 or the error code.</summary>
        public static int ActivateApp(string aumid)
        {
            var manager = (IApplicationActivationManager)new ApplicationActivationManager();
            try
            {
                return manager.ActivateApplication(aumid, null, 0, out _);
            }
            finally
            {
                Marshal.ReleaseComObject(manager);
            }
        }

        // ---- The battery ----

        [StructLayout(LayoutKind.Sequential)]
        struct SYSTEM_POWER_STATUS
        {
            public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag;
            public int BatteryLifeTime, BatteryFullLifeTime;
        }

        [DllImport("kernel32.dll")] static extern bool GetSystemPowerStatus(out SYSTEM_POWER_STATUS status);

        /// <summary>The battery: percent (or -1 when there is no battery), and whether the charger is in.</summary>
        public static (int percent, bool charging) Battery()
        {
            if (!GetSystemPowerStatus(out var s)) return (-1, false);
            bool noBattery = s.BatteryFlag == 128 || s.BatteryFlag == 255 || s.BatteryLifePercent > 100;
            return (noBattery ? -1 : s.BatteryLifePercent, s.ACLineStatus == 1);
        }

        // ---- Windows ----

        public const int GWL_EXSTYLE = -20;
        public const int WS_EX_TRANSPARENT = 0x20, WS_EX_TOOLWINDOW = 0x80, WS_EX_LAYERED = 0x80000, WS_EX_NOACTIVATE = 0x8000000;
        static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
        const uint SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOACTIVATE = 0x10, SWP_NOOWNERZORDER = 0x200;
        public const int SW_RESTORE = 9;

        [DllImport("user32.dll")] public static extern int GetWindowLong(IntPtr hwnd, int index);
        [DllImport("user32.dll")] public static extern int SetWindowLong(IntPtr hwnd, int index, int value);
        [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
        [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
        [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd, int command);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr hwnd, StringBuilder name, int max);

        /// <summary>Puts a window on top of everything again, without taking the focus.</summary>
        public static void KeepOnTop(IntPtr hwnd) =>
            SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOOWNERZORDER);

        public static string ClassOf(IntPtr hwnd)
        {
            var name = new StringBuilder(256);
            return GetClassName(hwnd, name, name.Capacity) > 0 ? name.ToString() : "";
        }
    }
}
