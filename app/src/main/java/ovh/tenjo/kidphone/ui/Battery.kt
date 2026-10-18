package ovh.tenjo.kidphone.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

data class BatteryInfo(val percent: Int, val charging: Boolean)

/** The battery right now (the system keeps the last battery broadcast). */
fun currentBattery(context: Context): BatteryInfo =
    parseBattery(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))

fun parseBattery(intent: Intent?): BatteryInfo {
    if (intent == null) return BatteryInfo(-1, false)
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    // "Plugged in", like the phone's own battery icon: a weak USB port can power the phone
    // without its charge going up, but it's still on the charger.
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ||
        status == BatteryManager.BATTERY_STATUS_CHARGING
    return BatteryInfo(if (level < 0) -1 else level * 100 / scale, charging)
}

@Composable
fun rememberBattery(): BatteryInfo {
    val context = LocalContext.current
    var info by remember { mutableStateOf(currentBattery(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                info = parseBattery(intent)
            }
        }
        // Exported: battery updates come from the system. Only the system can send this broadcast,
        // and on Android 12 and older a "not exported" receiver doesn't get system broadcasts.
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return info
}

/** Green above 50%, orange down to 21%, red at 20% and below. */
fun batteryColor(battery: BatteryInfo): Color = when {
    battery.percent < 0 -> Muted
    battery.percent <= 20 -> CallRed
    battery.percent <= 50 -> Color(0xFFF59E0B)
    else -> CallGreen
}

/** A small battery drawing: outline, cap, and a fill as long as the charge. */
@Composable
fun BatteryGlyph(percent: Int, fill: Color, outline: Color, modifier: Modifier = Modifier.size(width = 40.dp, height = 21.dp)) {
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val cap = 4.dp.toPx()
        val body = size.width - cap - 1.dp.toPx()
        drawRoundRect(
            color = outline,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(body - stroke, size.height - stroke),
            cornerRadius = CornerRadius(6.dp.toPx()),
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = outline,
            topLeft = Offset(body + 1.dp.toPx(), size.height * 0.32f),
            size = Size(cap, size.height * 0.36f),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
        val inset = stroke + 2.dp.toPx()
        drawRoundRect(
            color = fill,
            topLeft = Offset(inset, inset),
            size = Size((body - 2 * inset) * percent.coerceIn(0, 100) / 100f, size.height - 2 * inset),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
    }
}
