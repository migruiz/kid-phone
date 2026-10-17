package ovh.tenjo.kidphone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ovh.tenjo.kidphone.data.Contact
import ovh.tenjo.kidphone.hub.KidRequest

/**
 * After the child taps an "ask first" button: waiting for a parent's answer, then the answer.
 * [request] is null until the hub has taken the request; [error] when it couldn't be sent.
 */
@Composable
fun AskScreen(
    contact: Contact,
    request: KidRequest?,
    error: String?,
    onCall: () -> Unit,
    onStopAsking: () -> Unit,
    onClose: () -> Unit,
) {
    val name = contact.name.ifBlank { "them" }
    val waiting = error == null && (request == null || request.status == "pending")
    // The answer usually comes within a minute or two: keep the screen on so he sees it.
    val view = LocalView.current
    DisposableEffect(waiting) {
        view.keepScreenOn = waiting
        onDispose { view.keepScreenOn = false }
    }
    KidBackground {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                error != null -> Answer("😕", "Couldn't ask", error, "OK", onClose)

                waiting -> {
                    Text("🙋", fontSize = 72.sp)
                    Spacer(Modifier.height(16.dp))
                    Title("Asking Mum and Dad…")
                    Spacer(Modifier.height(10.dp))
                    Body("You can ring $name as soon as one of them says yes.")
                    Spacer(Modifier.height(28.dp))
                    CircularProgressIndicator(color = Color.White, strokeWidth = 5.dp, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(40.dp))
                    BigButton("Stop asking", onStopAsking, container = Color.White.copy(alpha = 0.12f))
                }

                request?.status == "approved" -> {
                    Text("🎉", fontSize = 72.sp)
                    Spacer(Modifier.height(16.dp))
                    Title("Yes!")
                    Spacer(Modifier.height(10.dp))
                    Body("You can ring $name for ${request.minutes ?: 0} minutes.")
                    Spacer(Modifier.height(36.dp))
                    BigButton("Call $name", onCall, container = CallGreen, icon = Icons.Rounded.Call)
                    Spacer(Modifier.height(12.dp))
                    BigButton("Later", onClose, container = Color.White.copy(alpha = 0.12f))
                }

                request?.status == "rejected" -> Answer("🙅", "Not now", "Mum or Dad said no this time.", "OK", onClose)

                else -> Answer("⏰", "No answer", "Mum and Dad didn't answer. Try again a bit later.", "OK", onClose)
            }
        }
    }
}

@Composable
private fun Answer(emoji: String, title: String, text: String, button: String, onClose: () -> Unit) {
    Text(emoji, fontSize = 72.sp)
    Spacer(Modifier.height(16.dp))
    Title(title)
    Spacer(Modifier.height(10.dp))
    Body(text)
    Spacer(Modifier.height(36.dp))
    BigButton(button, onClose, container = Color.White.copy(alpha = 0.12f))
}

@Composable
private fun Title(text: String) =
    Text(text, fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center)

@Composable
private fun Body(text: String) =
    Text(
        text, fontSize = 19.sp, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center,
        lineHeight = 26.sp, modifier = Modifier.fillMaxWidth(),
    )
