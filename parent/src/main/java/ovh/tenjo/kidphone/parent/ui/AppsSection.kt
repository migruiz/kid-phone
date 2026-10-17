package ovh.tenjo.kidphone.parent.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ovh.tenjo.kidphone.parent.Hub
import ovh.tenjo.kidphone.parent.KidApp
import ovh.tenjo.kidphone.ui.CallGreen
import ovh.tenjo.kidphone.ui.Group
import ovh.tenjo.kidphone.ui.Hairline
import ovh.tenjo.kidphone.ui.Ink
import ovh.tenjo.kidphone.ui.Muted
import java.net.HttpURLConnection
import java.net.URL

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

/** The apps on the child's phone, each with a switch. */
@Composable
fun AppsGroup(apps: List<KidApp>, onToggle: (KidApp, Boolean) -> Unit) {
    // Apps no longer on the phone only matter if they are still switched on.
    val shown = apps.filter { it.installed || it.allowed }
    Group {
        if (shown.isEmpty()) {
            Text(
                "No apps yet. They appear here once the child's phone has connected to the hub at home.",
                color = Muted, modifier = Modifier.padding(16.dp),
            )
        }
        shown.forEachIndexed { i, app ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 76.dp), color = Hairline)
            AppRow(app, onToggle)
        }
    }
}

@Composable
private fun AppRow(app: KidApp, onToggle: (KidApp, Boolean) -> Unit) {
    val warning = Warnings[app.packageName]
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                !app.installed -> Text("No longer on the phone", fontSize = 13.sp, color = Muted)
                warning != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(warning, fontSize = 13.sp, color = Color(0xFF92400E), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = app.allowed,
            onCheckedChange = { onToggle(app, it) },
            colors = SwitchDefaults.colors(checkedTrackColor = CallGreen),
        )
    }
}

/** The app's icon, fetched from the hub (it needs this phone's pairing token) and kept in memory. */
@Composable
private fun AppIcon(app: KidApp) {
    var bitmap by remember(app.packageName) { mutableStateOf(IconCache.get(app.packageName)) }
    if (bitmap == null && app.hasIcon) {
        LaunchedEffect(app.packageName) { bitmap = IconCache.load(app.packageName) }
    }
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF2F4F8)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = null, modifier = Modifier.size(42.dp))
        } else {
            Icon(Icons.Rounded.Android, contentDescription = null, tint = Muted)
        }
    }
}

private object IconCache {
    private val icons = HashMap<String, ImageBitmap>()

    fun get(packageName: String): ImageBitmap? = synchronized(icons) { icons[packageName] }

    suspend fun load(packageName: String): ImageBitmap? = withContext(Dispatchers.IO) {
        get(packageName)?.let { return@withContext it }
        runCatching {
            val conn = URL(Hub.iconUrl(packageName)).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Authorization", Hub.authHeader())
                conn.setRequestProperty("User-Agent", "KidPhone (Android)")
                if (conn.responseCode != 200) return@runCatching null
                BitmapFactory.decodeStream(conn.inputStream)?.asImageBitmap()
            } finally {
                conn.disconnect()
            }
        }.getOrNull()?.also { synchronized(icons) { icons[packageName] = it } }
    }
}
