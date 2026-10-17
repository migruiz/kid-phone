package ovh.tenjo.kidphone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** The answers a parent can give, or the new length of a running call time (0 = no / stop). */
private val Choices = listOf(10 to "10 min", 30 to "30 min", 60 to "1 hour")

/**
 * After a parent typed the PIN on the asking screen: answer the request here. Goes through the hub,
 * so the parents' phones show the same.
 */
@Composable
fun ParentAnswerScreen(name: String, busy: Boolean, error: String?, onAnswer: (Int) -> Unit, onCancel: () -> Unit) {
    ParentPage {
        ScreenHeader(
            "Let him ring $name?", onBack = onCancel,
            subtitle = "Your answer goes to Kid Phone Parent too, the same as answering there.",
        )
        Choices.forEach { (minutes, label) ->
            BigButton("Yes, for $label", { onAnswer(minutes) }, container = CallGreen, enabled = !busy)
            Spacer(Modifier.height(10.dp))
        }
        BigButton("No", { onAnswer(0) }, container = Danger, enabled = !busy)
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = Danger, fontWeight = FontWeight.Bold)
        }
    }
}

/** A call time running now, for the parent menu. */
data class CallTime(val name: String, val number: String, val minutesLeft: Long)

/**
 * The call times running now, at the top of the parent menu: change each to 10, 30 or 60 minutes
 * from now, or stop it. Through the hub, so Kid Phone Parent shows the same.
 */
@Composable
fun CallTimesCard(callTimes: List<CallTime>, busy: Boolean, error: String?, onChange: (CallTime, Int) -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFFDDF7E8), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            callTimes.forEachIndexed { i, ct ->
                if (i > 0) Spacer(Modifier.height(16.dp))
                Text(
                    "📞  He can ring ${ct.name} for ${ct.minutesLeft} more min",
                    fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B5D33),
                )
                Text("Change it to, from now:", fontSize = 14.sp, color = Color(0xFF2F7A52), modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choices.forEach { (minutes, label) ->
                        SmallChoice(label, CallGreen, enabled = !busy, Modifier.weight(1f)) { onChange(ct, minutes) }
                    }
                    SmallChoice("Stop", Danger, enabled = !busy, Modifier.weight(1f)) { onChange(ct, 0) }
                }
            }
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = Danger, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SmallChoice(label: String, color: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        color = if (enabled) color else color.copy(alpha = 0.4f), modifier = modifier.height(46.dp),
    ) {
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}
