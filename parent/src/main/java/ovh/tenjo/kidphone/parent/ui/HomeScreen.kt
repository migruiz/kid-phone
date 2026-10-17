package ovh.tenjo.kidphone.parent.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ovh.tenjo.kidphone.parent.Child
import ovh.tenjo.kidphone.parent.Hub
import ovh.tenjo.kidphone.parent.HubState
import ovh.tenjo.kidphone.ui.CallGreen
import ovh.tenjo.kidphone.ui.Danger
import ovh.tenjo.kidphone.ui.Group
import ovh.tenjo.kidphone.ui.Ink
import ovh.tenjo.kidphone.ui.Muted
import ovh.tenjo.kidphone.ui.ParentPage
import ovh.tenjo.kidphone.ui.RowDivider
import ovh.tenjo.kidphone.ui.ScreenHeader
import ovh.tenjo.kidphone.ui.SectionLabel
import ovh.tenjo.kidphone.ui.SettingsRow
import ovh.tenjo.kidphone.ui.UpdatesRow

/** The parent app's main screen: the child's phone, and (later) its apps and call requests. */
@Composable
fun HomeScreen() {
    var state by remember { mutableStateOf<HubState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Fresh every 10 seconds while the app is on screen.
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                runCatching { Hub.state() }
                    .onSuccess { state = it; error = null }
                    .onFailure { error = "Can't reach the home hub right now." }
                delay(10_000)
            }
        }
    }

    // Switches flipped here show at once, before the hub confirms.
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    var confirmReplace by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val childName = state?.child?.name?.ifBlank { null } ?: Hub.childName.ifBlank { "Your child" }

    ParentPage {
        ScreenHeader("$childName's phone", onBack = null)
        ChildCard(state?.child, error)

        state?.let { st ->
            Spacer(Modifier.height(20.dp))
            SectionLabel("Apps")
            Text(
                "Apps switched on here appear when $childName taps Apps. They only open on the home Wi-Fi.",
                color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            AppsGroup(
                apps = st.apps.map { a -> pending[a.packageName]?.let { a.copy(allowed = it) } ?: a },
                onToggle = { app, on ->
                    pending[app.packageName] = on
                    scope.launch {
                        runCatching { Hub.setAllowed(app.packageName, on) }
                            .onSuccess { runCatching { Hub.state() }.onSuccess { state = it } }
                            .onFailure { info = "Couldn't change ${app.label}. Check the internet connection and try again." }
                        pending.remove(app.packageName)
                    }
                },
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("This app")
        Group {
            UpdatesRow()
            RowDivider()
            SettingsRow(
                Icons.Rounded.Groups, Color(0xFF7442EA), "Parents",
                state?.parents?.joinToString(", ") { if (it.me) "${it.name} (you)" else it.name } ?: Hub.myName,
                onClick = {},
            )
            RowDivider()
            SettingsRow(
                Icons.Rounded.PhoneAndroid, Color(0xFFF2760B), "Replace $childName's phone",
                "For a new or reset phone", onClick = { confirmReplace = true },
            )
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { confirmUnpair = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Unpair this phone…", color = Danger, fontWeight = FontWeight.Bold)
        }
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            containerColor = Color.White,
            title = { Text("Replace $childName's phone?", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text(
                    "For the next 10 minutes, a phone running Kid Phone can connect to the hub as " +
                        "$childName's phone (Home hub → Connect). It takes over from the old one."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    scope.launch {
                        info = runCatching { Hub.replaceKid() }
                            .fold({ "Ready: connect the new phone within 10 minutes." }, { "Couldn't reach the hub." })
                    }
                }) { Text("Allow", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            containerColor = Color.White,
            title = { Text("Unpair this phone?", fontWeight = FontWeight.ExtraBold) },
            text = { Text("This phone stops getting $childName's requests. To pair again you need a new code from $childName's phone.") },
            confirmButton = {
                TextButton(onClick = { confirmUnpair = false; scope.launch { Hub.unpair() } }) {
                    Text("Unpair", color = Danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
    info?.let {
        AlertDialog(
            onDismissRequest = { info = null },
            containerColor = Color.White,
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { info = null }) { Text("OK", fontWeight = FontWeight.Bold) } },
        )
    }
}

/** Whether the child's phone has been in touch, and which version it runs. */
@Composable
private fun ChildCard(child: Child?, error: String?) {
    val (bg, dot, title, text) = when {
        error != null -> Quad(Color(0xFFFFE3E3), Danger, "Hub not reachable", error)
        child == null -> Quad(Color(0xFFE3E8F5), Muted, "Loading…", "")
        !child.paired -> Quad(
            Color(0xFFFFF1D6), Color(0xFFF59E0B), "Not connected yet",
            "On the child's phone: padlock → PIN → Home hub → Connect.",
        )
        child.lastSeenAgoMs == null -> Quad(Color(0xFFE3E8F5), Muted, "Connected", "Not heard from yet.")
        child.lastSeenAgoMs < 2 * 60_000 -> Quad(Color(0xFFDDF7E8), CallGreen, "At home", "Seen on the home Wi-Fi just now.")
        else -> Quad(
            Color(0xFFE3E8F5), Muted, "Seen ${ago(child.lastSeenAgoMs)}",
            "It checks in while its screen is on and it's on the home Wi-Fi.",
        )
    }
    Surface(shape = RoundedCornerShape(24.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(dot, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                if (text.isNotBlank()) Text(text, fontSize = 15.sp, color = Muted)
                child?.appVersion?.takeIf { it > 0 && error == null }?.let {
                    Text("Kid Phone ${versionName(it)}", fontSize = 13.sp, color = Muted)
                }
            }
        }
    }
}

private data class Quad(val bg: Color, val dot: Color, val title: String, val text: String)

/** 10203 → "1.2.3" */
fun versionName(code: Long): String = "${code / 10000}.${code / 100 % 100}.${code % 100}"

fun ago(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 48 * 60 -> "yesterday"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
