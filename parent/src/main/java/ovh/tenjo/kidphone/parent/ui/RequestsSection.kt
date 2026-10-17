package ovh.tenjo.kidphone.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ovh.tenjo.kidphone.parent.CallRequest
import ovh.tenjo.kidphone.parent.Notifications
import ovh.tenjo.kidphone.ui.CallGreen
import ovh.tenjo.kidphone.ui.Danger
import ovh.tenjo.kidphone.ui.Group
import ovh.tenjo.kidphone.ui.Hairline
import ovh.tenjo.kidphone.ui.Ink
import ovh.tenjo.kidphone.ui.Muted

/**
 * "Ask first" requests: the ones waiting for an answer (with No / 10 min / 30 min / 1 hour), the
 * call times running now, and the last few answers.
 */
@Composable
fun RequestsSection(
    childName: String,
    requests: List<CallRequest>,
    busy: Set<String>,
    onAnswer: (CallRequest, Int) -> Unit,
    /** Change a running call time: 0 stops it, otherwise minutes from now. */
    onChange: (CallRequest, Int) -> Unit,
) {
    val pending = requests.filter { it.status == "pending" }
    val running = requests.filter { it.status == "approved" && (it.remainingSeconds ?: 0) > 0 }
    val history = requests.filter { it !in pending && it !in running }.take(5)

    pending.forEach { r ->
        Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFFFFF1D6), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("🙋  $childName wants to ring ${r.contactName.ifBlank { r.number }}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                Text("Asked ${ago(r.agoMs)}. It stops waiting after 5 minutes.", fontSize = 14.sp, color = Color(0xFF92400E))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnswerButton("No", Danger, enabled = r.id !in busy, Modifier.weight(1f)) { onAnswer(r, 0) }
                    listOf(10 to "10 min", 30 to "30 min", 60 to "1 hour").forEach { (minutes, label) ->
                        AnswerButton(label, CallGreen, enabled = r.id !in busy, Modifier.weight(1f)) { onAnswer(r, minutes) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    running.forEach { r ->
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFDDF7E8), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "📞  $childName can ring ${r.contactName.ifBlank { r.number }} for ${((r.remainingSeconds ?: 0) + 59) / 60} more min",
                    fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B5D33),
                )
                Text(
                    "${r.decidedBy ?: "A parent"} said yes. Change it to, from now:",
                    fontSize = 14.sp, color = Color(0xFF2F7A52), modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10 to "10 min", 30 to "30 min", 60 to "1 hour").forEach { (minutes, label) ->
                        AnswerButton(label, CallGreen, enabled = r.id !in busy, Modifier.weight(1f)) { onChange(r, minutes) }
                    }
                    AnswerButton("Stop", Danger, enabled = r.id !in busy, Modifier.weight(1f)) { onChange(r, 0) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (history.isNotEmpty()) {
        Group {
            history.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = Hairline)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.contactName.ifBlank { r.number }, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text(
                            Notifications.outcome(r.status, r.minutes, if (r.status == "ended") r.endedBy else r.decidedBy),
                            fontSize = 14.sp, color = Muted,
                        )
                    }
                    Text(ago(r.agoMs), fontSize = 13.sp, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun AnswerButton(label: String, color: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        color = if (enabled) color else color.copy(alpha = 0.4f), modifier = modifier.height(48.dp),
    ) {
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}
