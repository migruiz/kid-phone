using System;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace KidLock.Launcher
{
    /// <summary>Kid Phone's look (shared/…/ui/Theme.kt and app/…/ui/KidScreen.kt): night sky, white, Nunito.</summary>
    static class Theme
    {
        public static readonly Color NightTop = C(0xFF161C36);
        public static readonly Color NightBottom = C(0xFF0A0E1C);
        public static readonly Color Brand = C(0xFF3B6EF5);
        public static readonly Color Green = C(0xFF22C55E);
        public static readonly Color Amber = C(0xFFF59E0B);
        public static readonly Color Red = C(0xFFEF4444);
        public static readonly Color Yellow = C(0xFFFFC83D);
        public static readonly Color Muted = C(0xFF6B7385);

        /// <summary>The call buttons' colours: a light and a dark shade each.</summary>
        public static readonly (Color light, Color dark)[] TileColors =
        {
            (C(0xFF5B9BFF), C(0xFF2E62EE)), // blue
            (C(0xFF3DDC97), C(0xFF10A36C)), // green
            (C(0xFFFFB547), C(0xFFF2760B)), // orange
            (C(0xFFAE8BFF), C(0xFF7442EA)), // purple
            (C(0xFF2ED3C4), C(0xFF0A9488)), // teal
            (C(0xFFFF6B9A), C(0xFFE23670)), // pink
        };

        public static (Color light, Color dark) TileColor(int index) => TileColors[((index % TileColors.Length) + TileColors.Length) % TileColors.Length];

        /// <summary>Nunito in four weights (SemiBold, Bold, ExtraBold, Black), embedded in the exe.</summary>
        public static readonly FontFamily Nunito = new FontFamily(new Uri("pack://application:,,,/"), "./Fonts/#Nunito, Segoe UI");

        public static Color C(uint argb) =>
            Color.FromArgb((byte)(argb >> 24), (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb);

        public static Color WithAlpha(this Color c, double alpha) => Color.FromArgb((byte)Math.Round(alpha * 255), c.R, c.G, c.B);

        public static SolidColorBrush Brush(Color c)
        {
            var b = new SolidColorBrush(c);
            b.Freeze();
            return b;
        }

        public static SolidColorBrush White(double alpha) => Brush(Colors.White.WithAlpha(alpha));

        public static TextBlock Text(string text, double size, FontWeight weight, Brush color = null) => new TextBlock
        {
            Text = text,
            FontFamily = Nunito,
            FontSize = size,
            FontWeight = weight,
            Foreground = color ?? Brushes.White,
        }.Crisp();

        static TextBlock Crisp(this TextBlock t)
        {
            TextOptions.SetTextFormattingMode(t, TextFormattingMode.Ideal);
            TextOptions.SetTextRenderingMode(t, TextRenderingMode.Grayscale);
            return t;
        }

        /// <summary>The page behind everything: night-sky gradient with a blue glow top left and a pink one bottom right.</summary>
        public static Grid NightSky()
        {
            var sky = new Grid
            {
                Background = new LinearGradientBrush(NightTop, NightBottom, 90),
                ClipToBounds = true,
            };
            var blue = Glow(C(0x553B6EF5));
            var pink = Glow(C(0x40E23670));
            sky.Children.Add(blue);
            sky.Children.Add(pink);
            sky.SizeChanged += (s, e) =>
            {
                double r = e.NewSize.Width * 0.9;
                foreach (var glow in new[] { blue, pink })
                {
                    glow.Width = glow.Height = 2 * r;
                    glow.HorizontalAlignment = HorizontalAlignment.Left;
                    glow.VerticalAlignment = VerticalAlignment.Top;
                }
                blue.Margin = new Thickness(-r, -r, 0, 0);
                pink.Margin = new Thickness(e.NewSize.Width - r, e.NewSize.Height - r, 0, 0);
            };
            return sky;
        }

        static System.Windows.Shapes.Ellipse Glow(Color color)
        {
            var brush = new RadialGradientBrush(color, Colors.Transparent);
            brush.Freeze();
            return new System.Windows.Shapes.Ellipse { Fill = brush, IsHitTestVisible = false };
        }

        /// <summary>
        /// A stand-in icon when Windows has none: the first letter in a round tile of the app's colour,
        /// like a call button without an emoji on the phone.
        /// </summary>
        public static BitmapSource LetterIcon(string label, int colorIndex, int size = 192)
        {
            var (light, dark) = TileColor(colorIndex);
            var visual = new DrawingVisual();
            using (var dc = visual.RenderOpen())
            {
                var fill = new LinearGradientBrush(light, dark, 45);
                dc.DrawRoundedRectangle(fill, null, new Rect(0, 0, size, size), size * 0.28, size * 0.28);
                var letter = string.IsNullOrWhiteSpace(label) ? "?" : label.Trim().Substring(0, 1).ToUpperInvariant();
                var text = new FormattedText(letter, CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
                    new Typeface(Nunito, FontStyles.Normal, FontWeights.Black, FontStretches.Normal), size * 0.58, Brushes.White, 1.0);
                dc.DrawText(text, new Point((size - text.Width) / 2, (size - text.Height) / 2));
            }
            var bitmap = new RenderTargetBitmap(size, size, 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(visual);
            bitmap.Freeze();
            return bitmap;
        }
    }
}
