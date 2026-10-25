using System;
using System.IO;
using System.Text;

namespace KidLock.Launcher
{
    /// <summary>A small log in %LOCALAPPDATA%\KidLock\launcher.log (kept under about 256 KB).</summary>
    static class Log
    {
        const long MaxBytes = 256 * 1024;
        static string path;
        static readonly object gate = new object();

        public static void Start()
        {
            try
            {
                var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "KidLock");
                Directory.CreateDirectory(dir);
                path = Path.Combine(dir, "launcher.log");
                var info = new FileInfo(path);
                if (info.Exists && info.Length > MaxBytes)
                {
                    // Keep the newer half.
                    var text = File.ReadAllText(path, Encoding.UTF8);
                    File.WriteAllText(path, text.Substring(text.Length / 2), Encoding.UTF8);
                }
            }
            catch
            {
                path = null; // no log is better than no home screen
            }
        }

        public static void Write(string line)
        {
            lock (gate)
            {
                try
                {
                    if (path != null) File.AppendAllText(path, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss}  {line}\r\n", Encoding.UTF8);
                }
                catch { }
            }
        }
    }
}
