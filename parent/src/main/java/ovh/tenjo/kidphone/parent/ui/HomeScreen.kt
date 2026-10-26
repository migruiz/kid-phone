package ovh.tenjo.kidphone.parent.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Computer
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ovh.tenjo.kidphone.parent.Battery
import ovh.tenjo.kidphone.parent.Child
import ovh.tenjo.kidphone.parent.Hub
import ovh.tenjo.kidphone.parent.HubState
import ovh.tenjo.kidphone.parent.Notifications
import ovh.tenjo.kidphone.parent.PcState
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
import ovh.tenjo.kidphone.ui.minutesLeft
import ovh.tenjo.kidphone.ui.pcAppWarning
import ovh.tenjo.kidphone.ui.UpdatesRow

/** The parent app's main screen: the child's phone, its call requests and apps, and the child's PC. */
@Composable
fun HomeScreen() {
    var state by remember { mutableStateOf<HubState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Fresh every 10 seconds while the app is on screen.
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            runCatching { Hub.syncFcmToken() }
            while (true) {
                runCatching { Hub.state() }
                    .onSuccess { state = it; error = null }
                    .onFailure { error = "Can't reach the home hub right now." }
                delay(10_000)
            }
        }
    }

    // Switches flipped here show at once, before the hub confirms (the PC's apart, as a PC app's
    // name can be the same as a phone app's).
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    val pendingPc = remember { mutableStateMapOf<String, Boolean>() }
    // Requests being answered from this screen.
    val answering = remember { mutableStateMapOf<String, Boolean>() }
    val context = LocalContext.current
    var confirmReplace by remember { mutableStateOf(false) }
    var confirmReplacePc by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val childName = state?.child?.name?.ifBlank { null } ?: Hub.childName.ifBlank { "Your child" }
    // The apps list is its own screen, opened from the "Apps" row.
    var showApps by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showApps) { showApps = false }
    // So is the PC's, opened from its row.
    var showPc by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showPc) { showPc = false }

    if (showApps) {
        ParentPage {
            ScreenHeader(
                "Apps", onBack = { showApps = false },
                subtitle = "Apps switched on here appear when $childName taps Apps. They only open on the home Wi-Fi; " +
                    "while $childName is out, a parent can give an app time with the PIN on $childName's phone. " +
                    "Switching one on asks for how long; tap it later to change the time. With a time, " +
                    "$childName sees \"10 minutes left\", \"5 minutes left\" and a countdown, then it closes.",
            )
            val st = state
            if (st == null) {
                Text(error ?: "Loading…", color = Muted)
            } else {
                AppsGroup(
                    apps = st.apps.map { a -> pending[a.packageName]?.let { a.copy(allowed = it) } ?: a },
                    onChange = { app, on, minutes ->
                        pending[app.packageName] = on
                        scope.launch {
                            runCatching { Hub.setAllowed(app.packageName, on, minutes) }
                                .onSuccess { runCatching { Hub.state() }.onSuccess { state = it } }
                                .onFailure { info = "Couldn't change ${app.label}. Check the internet connection and try again." }
                            pending.remove(app.packageName)
                        }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    } else if (showPc) {
        ParentPage {
            ScreenHeader(
                "$childName's PC", onBack = { showPc = false },
                subtitle = "Apps switched on here open on $childName's PC, in his own Windows account, while the PC is at home. " +
                    "Switching one on asks for how long; tap it later to change the time. With a time, " +
                    "$childName sees \"10 minutes left\", \"5 minutes left\" and a 30-second countdown, then it closes.",
            )
            val pc = state?.pc
            if (pc == null) {
                Text(error ?: "Loading…", color = Muted)
            } else {
                AppsGroup(
                    apps = pc.apps.map { a -> pendingPc[a.packageName]?.let { a.copy(allowed = it) } ?: a },
                    iconUrl = Hub::pcIconUrl,
                    placeholder = Icons.Rounded.Computer,
                    emptyText = "No apps yet. They appear here once $childName's PC has connected to the hub at home.",
                    warningOf = ::pcAppWarning,
                    goneText = "No longer on the PC",
                    onChange = { app, on, minutes ->
                        pendingPc[app.packageName] = on
                        scope.launch {
                            runCatching { Hub.setPcAllowed(app.packageName, on, minutes) }
                                .onSuccess { runCatching { Hub.state() }.onSuccess { state = it } }
                                .onFailure { info = "Couldn't change ${app.label} on the PC. Check the internet connection and try again." }
                            pendingPc.remove(app.packageName)
                        }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    } else ParentPage {
        ScreenHeader("$childName's phone", onBack = null)
        ChildCard(state?.child, error)

        state?.let { st ->
            if (hasCurrentRequests(st.requests)) {
                Spacer(Modifier.height(20.dp))
                SectionLabel("Calls to ask about")
                RequestsSection(
                    childName, st.requests, answering.keys,
                    onAnswer = { r, minutes ->
                        answering[r.id] = true
                        scope.launch {
                            runCatching { Hub.answer(r.id, minutes) }
                                .onSuccess { res ->
                                    Notifications.cancel(context, r.id)
                                    if (res.alreadyAnswered) info = "Already answered: ${Notifications.outcome(res.request.status, res.request.minutes, res.request.decidedBy)}."
                                    runCatching { Hub.state() }.onSuccess { state = it }
                                }
                                .onFailure { info = "Couldn't send the answer. Check the internet connection and try again." }
                            answering.remove(r.id)
                        }
                    },
                    onChange = { r, minutes ->
                        answering[r.id] = true
                        scope.launch {
                            runCatching { Hub.changeWindow(r.id, minutes) }
                                .onSuccess { runCatching { Hub.state() }.onSuccess { state = it } }
                                .onFailure { e ->
                                    info = if ((e as? ovh.tenjo.kidphone.shared.HttpError)?.code == "not_running") {
                                        "That call time has already ended."
                                    } else {
                                        "Couldn't change it. Check the internet connection and try again."
                                    }
                                    runCatching { Hub.state() }.onSuccess { state = it }
                                }
                            answering.remove(r.id)
                        }
                    },
                )
            }
            Spacer(Modifier.height(20.dp))
            SectionLabel("Apps")
            Group {
                val on = st.apps.count { it.allowed && it.installed }
                val installed = st.apps.count { it.installed }
                // The app whose time runs out first, if any.
                val timed = st.apps.filter { it.allowed && it.endsAt != null }.minByOrNull { it.endsAt!! }
                SettingsRow(
                    Icons.Rounded.Apps, Color(0xFF0A9488), "Apps $childName can open",
                    when {
                        installed == 0 -> "None on the phone yet"
                        on == 0 -> "None switched on, of $installed"
                        timed != null -> "$on switched on · ${timed.label}: ${minutesLeft(timed.endsAt!!)} min left"
                        else -> "$on switched on, of $installed"
                    },
                    onClick = { showApps = true },
                )
            }
            st.pc?.takeIf { it.paired }?.let { pc ->
                Spacer(Modifier.height(20.dp))
                SectionLabel("$childName's PC")
                Group {
                    SettingsRow(
                        Icons.Rounded.Computer, PcBlue, "Apps on $childName's PC", pcAppsSummary(pc),
                        onClick = { showPc = true },
                    )
                }
                pcAway(pc)?.let {
                    Text(it, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                }
            }
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
            if (state?.pc?.paired == true) {
                RowDivider()
                SettingsRow(
                    Icons.Rounded.Computer, Color(0xFFF2760B), "Replace $childName's PC",
                    "For a reinstalled PC", onClick = { confirmReplacePc = true },
                )
            }
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
    if (confirmReplacePc) {
        AlertDialog(
            onDismissRequest = { confirmReplacePc = false },
            containerColor = Color.White,
            title = { Text("Replace $childName's PC?", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text(
                    "For the next 10 minutes, a newly set-up PC can connect to the hub as " +
                        "$childName's PC. It takes over from the old one."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplacePc = false
                    scope.launch {
                        info = runCatching { Hub.replacePc() }
                            .fold({ "Ready: connect the new PC within 10 minutes." }, { "Couldn't reach the hub." })
                    }
                }) { Text("Allow", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmReplacePc = false }) { Text("Cancel") } },
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
                child?.battery?.takeIf { error == null }?.let { BatteryLine(it) }
                child?.appVersion?.takeIf { it > 0 && error == null }?.let {
                    Text("Kid Phone ${versionName(it)}", fontSize = 13.sp, color = Muted)
                }
            }
        }
    }
}

/** "Battery 23%, charging", red below 15% and orange below 25%, with its age once it's old. */
@Composable
private fun BatteryLine(battery: Battery) {
    val color = when {
        battery.charging -> Ink
        battery.percent < 15 -> Danger
        battery.percent < 25 -> Color(0xFFC2410C)
        else -> Ink
    }
    val parts = listOfNotNull(
        "Battery ${battery.percent}%",
        "charging".takeIf { battery.charging },
        ago(battery.agoMs).takeIf { battery.agoMs >= 15 * 60_000 },
    )
    Text(
        parts.joinToString(", "), fontSize = 15.sp, color = color,
        fontWeight = if (color == Ink) FontWeight.SemiBold else FontWeight.ExtraBold,
    )
}

private data class Quad(val bg: Color, val dot: Color, val title: String, val text: String)

private val PcBlue = Color(0xFF2563EB)

/** The PC row's line, like the phone's Apps row: how many are on, or the time left of the one ending first. */
private fun pcAppsSummary(pc: PcState): String {
    val on = pc.apps.count { it.allowed && it.installed }
    val installed = pc.apps.count { it.installed }
    val timed = pc.apps.filter { it.allowed && it.endsAt != null }.minByOrNull { it.endsAt!! }
    return when {
        installed == 0 -> "None on the PC yet"
        on == 0 -> "None switched on, of $installed"
        timed != null -> "$on switched on · ${timed.label}: ${minutesLeft(timed.endsAt!!)} min left"
        else -> "$on switched on, of $installed"
    }
}

/**
 * A line under the PC row when the PC hasn't been in touch for over 2 minutes (it checks in all
 * the time while it is on at home), or null while it is.
 */
private fun pcAway(pc: PcState): String? {
    val seen = pc.lastSeenAgoMs ?: return "PC not heard from yet"
    return if (seen < 2 * 60_000) null else "PC off or asleep · seen ${ago(seen)}"
}

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
