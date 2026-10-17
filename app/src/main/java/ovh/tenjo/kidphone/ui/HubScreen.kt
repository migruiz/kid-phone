package ovh.tenjo.kidphone.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhonelinkSetup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ovh.tenjo.kidphone.hub.HomeStatus
import ovh.tenjo.kidphone.hub.Hub

/** One line about the hub for the parent menu. */
fun hubSummary(status: HomeStatus): String = when (status) {
    HomeStatus.NotSetUp -> "Not connected"
    HomeStatus.Checking -> "Connected"
    HomeStatus.AtHome -> "Connected, on the home Wi-Fi"
    HomeStatus.Away -> "Connected, but can't reach it now"
}

/**
 * Connects this phone to the home hub on the Raspberry Pi, and pairs the parents' phones with it.
 * The hub is how the parent app sees this phone's apps and answers "ask first" calls.
 */
@Composable
fun HubScreen(childName: String, onBack: () -> Unit) {
    val status by Hub.status.collectAsState()
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(Hub.address) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf(false) }

    ParentPage {
        ScreenHeader(
            "Home hub", onBack,
            subtitle = "The hub is a small program on the home Raspberry Pi. Through it, Kid Phone Parent " +
                "on your phones chooses which apps this phone can open and answers \"ask first\" calls. " +
                "This phone only reaches it on the home Wi-Fi.",
        )
        if (status == HomeStatus.NotSetUp) {
            Group {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = address, onValueChange = { address = it.take(60) },
                        label = { Text("Hub address") }, singleLine = true,
                        shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    BigButton(
                        if (busy) "Connecting…" else "Connect", icon = Icons.Rounded.Link, enabled = !busy,
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                runCatching { Hub.connect(address, childName) }.onFailure { error = it.message }
                                busy = false
                            }
                        },
                    )
                }
            }
        } else {
            StatusCard(status)
            Spacer(Modifier.height(16.dp))
            BigButton(
                if (busy) "Asking the hub…" else "Add a parent phone", icon = Icons.Rounded.PhonelinkSetup,
                enabled = !busy && status != HomeStatus.Away,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { code = Hub.pairingCode() }.onFailure { error = "Couldn't get a code: ${it.message}" }
                        busy = false
                    }
                },
            )
            Text(
                "Shows a code to type into Kid Phone Parent on your phone. Do this once on each parent's phone.",
                color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text("Address: ${Hub.address}", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
            TextButton(onClick = { confirmForget = true }) {
                Text("Disconnect from the hub…", color = Danger, fontWeight = FontWeight.Bold)
            }
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = Danger, fontWeight = FontWeight.Bold)
        }
    }

    code?.let { PairingCodeDialog(it, onDismiss = { code = null }) }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            containerColor = Color.White,
            title = { Text("Disconnect?", fontWeight = FontWeight.ExtraBold) },
            text = { Text("The parent app stops seeing this phone until it is connected again.") },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; Hub.forget() }) {
                    Text("Disconnect", color = Danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

private class StatusLook(val background: Color, val dot: Color, val title: String, val text: String)

@Composable
private fun StatusCard(status: HomeStatus) {
    val look = when (status) {
        HomeStatus.AtHome -> StatusLook(Color(0xFFDDF7E8), CallGreen, "Connected", "On the home Wi-Fi.")
        HomeStatus.Away -> StatusLook(
            Color(0xFFFFF1D6), Color(0xFFF59E0B), "Can't reach the hub",
            "Not on the home Wi-Fi, or the Raspberry Pi is off.",
        )
        else -> StatusLook(Color(0xFFE3E8F5), Muted, "Connected", "Checking…")
    }
    Surface(shape = RoundedCornerShape(24.dp), color = look.background, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(look.dot, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(look.title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                Text(look.text, fontSize = 15.sp, color = Muted)
            }
        }
    }
}

/** The pairing code, big, with how long it still works. */
@Composable
private fun PairingCodeDialog(code: String, onDismiss: () -> Unit) {
    var secondsLeft by remember { mutableIntStateOf(600) }
    LaunchedEffect(code) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("Pair a parent phone", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("In Kid Phone Parent, type:", color = Muted)
                Spacer(Modifier.height(12.dp))
                Text(
                    code.chunked(3).joinToString(" "),
                    fontSize = 44.sp, fontWeight = FontWeight.Black, color = Ink, letterSpacing = 4.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Works once, for ${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')} more.",
                    color = Muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", fontWeight = FontWeight.Bold) } },
    )
}
