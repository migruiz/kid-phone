package ovh.tenjo.kidphone.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ovh.tenjo.kidphone.shared.R

// ---- Type ----

@OptIn(ExperimentalTextApi::class)
private fun nunito(weight: Int) = Font(
    R.font.nunito,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Nunito: round, friendly and very readable. One variable font file covers every weight. */
val Nunito = FontFamily(nunito(400), nunito(500), nunito(600), nunito(700), nunito(800), nunito(900))

private fun TextStyle.nunito() = copy(fontFamily = Nunito)

private val KidTypography = Typography().run {
    Typography(
        displayLarge = displayLarge.nunito(), displayMedium = displayMedium.nunito(), displaySmall = displaySmall.nunito(),
        headlineLarge = headlineLarge.nunito(), headlineMedium = headlineMedium.nunito(), headlineSmall = headlineSmall.nunito(),
        titleLarge = titleLarge.nunito(), titleMedium = titleMedium.nunito(), titleSmall = titleSmall.nunito(),
        bodyLarge = bodyLarge.nunito(), bodyMedium = bodyMedium.nunito(), bodySmall = bodySmall.nunito(),
        labelLarge = labelLarge.nunito(), labelMedium = labelMedium.nunito(), labelSmall = labelSmall.nunito(),
    )
}

// ---- Colour ----

val Ink = Color(0xFF1D2433)
val Muted = Color(0xFF6B7385)
val Hairline = Color(0xFFE6E9F0)
val PageTop = Color(0xFFF8F9FD)
val PageBottom = Color(0xFFECEFF7)
val KeySurface = Color(0xFFE9EDF5)
val Brand = Color(0xFF3B6EF5)
val CallGreen = Color(0xFF22C55E)
val CallRed = Color(0xFFEF4444)
val Danger = Color(0xFFD92D20)

val PageBrush = Brush.verticalGradient(listOf(PageTop, PageBottom))

/** A call button's colour: a light and a dark shade, drawn as a diagonal gradient. */
data class TileColor(val light: Color, val dark: Color) {
    val brush: Brush get() = Brush.linearGradient(listOf(light, dark))
}

val TileColors = listOf(
    TileColor(Color(0xFFFF6B9A), Color(0xFFE23670)), // pink
    TileColor(Color(0xFF5B9BFF), Color(0xFF2E62EE)), // blue
    TileColor(Color(0xFF3DDC97), Color(0xFF10A36C)), // green
    TileColor(Color(0xFFFFB547), Color(0xFFF2760B)), // orange
    TileColor(Color(0xFFAE8BFF), Color(0xFF7442EA)), // purple
    TileColor(Color(0xFF2ED3C4), Color(0xFF0A9488)), // teal
)

fun tileColor(index: Int): TileColor = TileColors[Math.floorMod(index, TileColors.size)]

@Composable
fun KidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Brand,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDDE6FF),
            onPrimaryContainer = Color(0xFF14307A),
            secondaryContainer = Color(0xFFE3E8F5),
            onSecondaryContainer = Ink,
            background = PageTop,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            onSurfaceVariant = Muted,
            outline = Color(0xFFC9CFDC),
            outlineVariant = Hairline,
            error = Danger,
        ),
        typography = KidTypography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

// ---- Shared pieces ----

/** Shrinks a little while pressed and springs back: a tactile "press" for big buttons. */
@Composable
fun Modifier.bounceClick(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
        label = "press",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

/** A round picture: the person's emoji, or the first letter of their name. */
@Composable
fun Avatar(name: String, emoji: String, size: Dp, background: Color, letterColor: Color = Color.White) {
    // Sized from the circle, not the phone's text-size setting, so it always fits.
    val density = LocalDensity.current
    val emojiSize = with(density) { (size * 0.52f).toSp() }
    val letterSize = with(density) { (size * 0.44f).toSp() }
    Box(Modifier.size(size).background(background, CircleShape), contentAlignment = Alignment.Center) {
        if (emoji.isNotBlank()) {
            Text(emoji, fontSize = emojiSize)
        } else {
            Text(
                name.trim().take(1).uppercase().ifEmpty { "?" },
                fontSize = letterSize, fontWeight = FontWeight.Black, color = letterColor,
            )
        }
    }
}

/** Title row of the parent screens, with a round back button when there is somewhere to go. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        if (onBack != null) {
            Surface(
                onClick = onBack,
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 1.dp,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Ink, lineHeight = 36.sp)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, fontSize = 16.sp, color = Muted, lineHeight = 22.sp)
        }
    }
}

/** A small grey caps label above a group of settings. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
    )
}

/** A big rounded action button. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Brand,
    content: Color = Color.White,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        color = if (enabled) container else container.copy(alpha = 0.4f),
        modifier = modifier.fillMaxWidth().height(60.dp),
    ) {
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = content)
        }
    }
}
