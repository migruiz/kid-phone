package ovh.tenjo.kidphone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/**
 * One app with its on/off switch, as both apps list them: icon, name, a warning or "No longer on
 * the phone" underneath, and the switch.
 */
@Composable
fun AppSwitchRow(
    packageName: String,
    label: String,
    installed: Boolean,
    allowed: Boolean,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val warning = appWarning(packageName)
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
        Switch(
            checked = allowed,
            onCheckedChange = onToggle,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = CallGreen),
        )
    }
}
