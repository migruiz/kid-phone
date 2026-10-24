package ovh.tenjo.kidphone.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Date

/**
 * Apps worth a second look before switching them on. The phone can't see what happens inside an
 * app, so an app with calls or chat lets the child talk to anyone, "ask first" or not.
 */
private val Warnings: Map<String, String> = buildMap {
    val talk = "Can message or call people"
    listOf(
        "com.whatsapp", "org.telegram.messenger", "com.facebook.orca", "com.facebook.katana",
        "com.instagram.android", "com.snapchat.android", "com.discord", "com.google.android.apps.messaging",
        "com.android.mms", "com.samsung.android.messaging", "com.roblox.client", "com.google.android.gm",
        "com.google.android.apps.tachyon", "com.skype.raider", "com.zhiliaoapp.musically",
        "ovh.tenjo.agentphone", "com.google.android.apps.dynamite",
    ).forEach { put(it, talk) }
    val web = "Opens any website"
    listOf(
        "com.android.chrome", "org.mozilla.firefox", "com.google.android.googlequicksearchbox",
        "com.sec.android.app.sbrowser", "com.opera.browser", "com.microsoft.emmx", "com.brave.browser",
        "com.duckduckgo.mobile.android",
    ).forEach { put(it, web) }
    put("com.google.android.youtube", "Any YouTube video (this isn't YouTube Kids)")
}

fun appWarning(packageName: String): String? = Warnings[packageName]

/** How long an app can be switched on for, counted from when a parent chooses it. */
val AppTimeChoices = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours")

/** Whole minutes left until [endsAt] (wall clock), at least 1. */
fun minutesLeft(endsAt: Long, now: Long = System.currentTimeMillis()): Long =
    ((endsAt - now + 59_999) / 60_000).coerceAtLeast(1)

/** The current time, updated every 20 seconds, for minutes-left labels. */
@Composable
fun rememberClock(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(20_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * One app with its on/off switch, as both apps list them: icon, name, a warning or "No longer on
 * the phone" underneath, and the switch. With [onTime], a timer button before the switch opens
 * the time choices; it shows the minutes left when the app is on for a while ([endsAt]).
 */
@Composable
fun AppSwitchRow(
    packageName: String,
    label: String,
    installed: Boolean,
    allowed: Boolean,
    enabled: Boolean = true,
    endsAt: Long? = null,
    onTime: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val warning = appWarning(packageName)
    val now = rememberClock()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF2F4F8)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                !installed -> Text("No longer on the phone", fontSize = 13.sp, color = Muted)
                warning != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(warning, fontSize = 13.sp, color = Color(0xFF92400E), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (onTime != null && (installed || allowed)) {
            val timed = allowed && endsAt != null
            Surface(
                onClick = onTime,
                enabled = enabled,
                shape = CircleShape,
                color = if (timed) CallGreen.copy(alpha = 0.16f) else Color(0xFFF2F4F8),
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Timer, contentDescription = "Time",
                        tint = if (timed) TimedGreen else Muted, modifier = Modifier.size(18.dp),
                    )
                    if (timed) {
                        Spacer(Modifier.width(4.dp))
                        Text("${minutesLeft(endsAt!!, now)} min", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = TimedGreen)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        Switch(
            checked = allowed,
            onCheckedChange = onToggle,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = CallGreen),
        )
    }
}

private val TimedGreen = Color(0xFF0B5D33)

/**
 * How long an app stays on: [AppTimeChoices] from now, no time limit, or off. [onChoose] gets
 * (true, minutes) for a time, (true, null) for no time limit and (false, null) for off. Both
 * apps show it from the timer button on an app's row.
 */
@Composable
fun AppTimeDialog(label: String, allowed: Boolean, endsAt: Long?, onChoose: (Boolean, Int?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val now = rememberClock()
    val intro = when {
        allowed && endsAt != null -> {
            val until = DateFormat.getTimeFormat(context).format(Date(endsAt))
            "On until $until, ${minutesLeft(endsAt, now)} min left. It switches off by itself then. Change it to, from now:"
        }
        allowed -> "On, with no time limit. Give it a time, from now:"
        else -> "Switch it on for:"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text(label, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column {
                Text(intro, color = Muted, fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                AppTimeChoices.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (minutes, text) ->
                            ChoiceButton(text, CallGreen, Modifier.weight(1f)) { onChoose(true, minutes) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (!(allowed && endsAt == null)) {
                    ChoiceButton("No time limit", Brand, Modifier.fillMaxWidth()) { onChoose(true, null) }
                    Spacer(Modifier.height(8.dp))
                }
                if (allowed) ChoiceButton("Switch off now", Danger, Modifier.fillMaxWidth()) { onChoose(false, null) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = color, modifier = modifier.height(48.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}
