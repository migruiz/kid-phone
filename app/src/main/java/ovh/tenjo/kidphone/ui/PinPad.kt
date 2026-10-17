package ovh.tenjo.kidphone.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ovh.tenjo.kidphone.data.PinCheck
import ovh.tenjo.kidphone.data.Store
import kotlin.math.roundToInt

const val PIN_MIN = 4
const val PIN_MAX = 8

/**
 * A big number keypad. Typed digits show as dots, never as numbers. [errorCount] going up makes
 * the dots shake and the phone buzz, so a wrong PIN is obvious.
 */
@Composable
fun PinPad(
    title: String,
    message: String?,
    onSubmit: (String) -> Unit,
    onCancel: (() -> Unit)?,
    subtitle: String? = null,
    errorCount: Int = 0,
) {
    var pin by remember { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(errorCount) {
        if (errorCount == 0) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        shake.animateTo(0f, keyframes {
            durationMillis = 420
            -22f at 50
            22f at 110
            -16f at 170
            16f at 230
            -8f at 290
            8f at 350
        })
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(PageBrush)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // The keypad gets just over half the height (keys up to 78dp); the title sits centred
        // above it. On short screens the padlock picture is left out to make room.
        val compact = maxHeight < 640.dp
        val gap = 12.dp
        val keySize = minOf(78.dp, (maxWidth - gap * 4) / 3, (maxHeight * 0.56f - gap * 3) / 4)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!compact) {
                    Box(
                        Modifier.size(64.dp).background(Brand.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = Brand, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text(title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Ink, textAlign = TextAlign.Center)
                if (subtitle != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(subtitle, fontSize = 15.sp, color = Muted, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(if (compact) 16.dp else 26.dp))
                Row(
                    Modifier.offset { IntOffset(shake.value.roundToInt(), 0) },
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    repeat(maxOf(PIN_MIN, pin.length)) { i -> PinDot(filled = i < pin.length, error = message != null && pin.isEmpty()) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    message ?: "",
                    color = Danger, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    minLines = 2,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("<", "0", "OK"))
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { key ->
                            when (key) {
                                "<" -> RoundKey(onClick = { pin = pin.dropLast(1) }, size = keySize) {
                                    Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Delete", tint = Ink, modifier = Modifier.size(keySize * 0.36f))
                                }
                                "OK" -> RoundKey(
                                    onClick = {
                                        val typed = pin
                                        pin = ""
                                        onSubmit(typed)
                                    },
                                    enabled = pin.length >= PIN_MIN,
                                    color = Brand,
                                    size = keySize,
                                ) {
                                    Icon(Icons.Rounded.Check, contentDescription = "OK", tint = Color.White, modifier = Modifier.size(keySize * 0.42f))
                                }
                                else -> RoundKey(onClick = { if (pin.length < PIN_MAX) pin += key }, size = keySize) {
                                    Text(key, fontSize = (keySize.value * 0.38f).sp, fontWeight = FontWeight.Bold, color = Ink)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text("Cancel", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Muted)
                }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun PinDot(filled: Boolean, error: Boolean) {
    val size by animateDpAsState(if (filled) 18.dp else 16.dp, label = "dot")
    val color by animateColorAsState(
        when {
            filled -> Ink
            error -> Danger.copy(alpha = 0.15f)
            else -> Color.Transparent
        },
        label = "dotColor",
    )
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .background(color, CircleShape)
                .border(2.dp, if (error && !filled) Danger else Ink.copy(alpha = if (filled) 1f else 0.35f), CircleShape)
        )
    }
}

/** A round keypad key that bounces when pressed. */
@Composable
fun RoundKey(
    onClick: () -> Unit,
    enabled: Boolean = true,
    color: Color = KeySurface,
    size: Dp = 76.dp,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .bounceClick(enabled = enabled, onClick = onClick)
            .background(if (enabled) color else color.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Asks for the parent PIN and calls [onUnlocked] when it is right. */
@Composable
fun ParentPinScreen(store: Store, onUnlocked: () -> Unit, onCancel: () -> Unit) {
    var message by remember { mutableStateOf<String?>(null) }
    var errors by remember { mutableIntStateOf(0) }
    PinPad(
        title = "Parent PIN",
        subtitle = "Type the PIN to open the parent settings.",
        message = message,
        errorCount = errors,
        onCancel = onCancel,
        onSubmit = { pin ->
            when (val r = store.checkPin(pin)) {
                PinCheck.Ok -> {
                    message = null
                    onUnlocked()
                }
                is PinCheck.Wrong -> {
                    message = "Wrong PIN. ${r.triesLeft} ${if (r.triesLeft == 1) "try" else "tries"} left."
                    errors++
                }
                is PinCheck.Wait -> {
                    message = "Too many wrong tries.\nWait ${formatWait(r.seconds)}."
                    errors++
                }
            }
        },
    )
}

private fun formatWait(seconds: Long): String =
    if (seconds >= 60) "${(seconds + 59) / 60} min" else "$seconds s"

/** Choosing a new PIN: type it, then type it again. */
@Composable
fun NewPinScreen(title: String, onDone: (String) -> Unit, onCancel: (() -> Unit)?) {
    var first by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var errors by remember { mutableIntStateOf(0) }
    PinPad(
        title = if (first == null) title else "Type it again",
        subtitle = if (first == null) "$PIN_MIN to $PIN_MAX digits. Only parents should know it." else "To make sure it's right.",
        message = message,
        errorCount = errors,
        onCancel = onCancel,
        onSubmit = { pin ->
            val firstPin = first
            when {
                firstPin == null -> {
                    first = pin
                    message = null
                }
                firstPin == pin -> onDone(pin)
                else -> {
                    first = null
                    message = "The two PINs were different.\nStart again."
                    errors++
                }
            }
        },
    )
}
