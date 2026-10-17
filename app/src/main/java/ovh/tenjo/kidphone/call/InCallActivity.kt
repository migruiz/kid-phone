package ovh.tenjo.kidphone.call

import android.os.Bundle
import android.os.PowerManager
import android.telecom.Call
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ovh.tenjo.kidphone.data.Store
import ovh.tenjo.kidphone.kiosk.Loudness
import ovh.tenjo.kidphone.ui.Avatar
import ovh.tenjo.kidphone.ui.CallGreen
import ovh.tenjo.kidphone.ui.CallRed
import ovh.tenjo.kidphone.ui.Ink
import ovh.tenjo.kidphone.ui.KidTheme
import ovh.tenjo.kidphone.ui.bounceClick
import ovh.tenjo.kidphone.ui.tileColor

/** Our own call screen: answer / decline while ringing, speaker and hang up during a call. */
class InCallActivity : ComponentActivity() {
    private var proximity: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Dark background, so the clock and icons at the top are drawn in white.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        onBackPressedDispatcher.addCallback(this) { /* stay on the call screen */ }

        val pm = getSystemService(PowerManager::class.java)
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "kidphone:incall")
        }

        setContent {
            KidTheme {
                val info by CallManager.current.collectAsState()
                val call = info
                LaunchedEffect(call == null) { if (call == null) finish() }
                LaunchedEffect(call?.state, call?.speaker) { updateProximity(call) }
                if (call != null) {
                    CallScreen(
                        call,
                        onAnswer = CallManager::answer,
                        onHangUp = CallManager::hangUp,
                        onSpeaker = { CallManager.setSpeaker(!call.speaker) },
                    )
                }
            }
        }
    }

    /** Turns the screen off when the phone is held to the ear, so a cheek can't press buttons. */
    private fun updateProximity(call: CallInfo?) {
        val lock = proximity ?: return
        val wanted = call != null && !call.speaker &&
            call.state in setOf(Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_CONNECTING)
        if (wanted && !lock.isHeld) lock.acquire(2 * 60 * 60 * 1000L)
        if (!wanted && lock.isHeld) lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
    }

    override fun onDestroy() {
        proximity?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /** In kid mode the volume buttons do nothing here either: calls stay at full volume. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Store.get(this).locked && Loudness.isVolumeKey(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }
}

private val Night = Color(0xFF0E1424)

@Composable
private fun CallScreen(call: CallInfo, onAnswer: () -> Unit, onHangUp: () -> Unit, onSpeaker: () -> Unit) {
    val ringing = call.state == Call.STATE_RINGING && call.incoming
    val waiting = call.state in setOf(Call.STATE_RINGING, Call.STATE_DIALING, Call.STATE_CONNECTING)
    val title = call.name ?: call.number.ifBlank { "Unknown" }
    // The caller's button colour fades into night blue; unknown callers get slate.
    val top = if (call.color >= 0) lerp(tileColor(call.color).dark, Night, 0.15f) else Color(0xFF334155)
    val background = Brush.verticalGradient(listOf(top, lerp(top, Night, 0.75f), Night))

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.connectedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val heading = when {
        ringing -> "Incoming call"
        call.state == Call.STATE_ACTIVE -> "On the phone"
        call.state == Call.STATE_HOLDING -> "On hold"
        call.state == Call.STATE_DISCONNECTING || call.state == Call.STATE_DISCONNECTED -> "Call ended"
        else -> "Calling"
    }
    val status = when (call.state) {
        Call.STATE_RINGING -> if (call.incoming) "is calling you" else "Ringing…"
        Call.STATE_ACTIVE -> {
            val secs = ((now - (call.connectedAt ?: now)) / 1000).coerceAtLeast(0)
            "%d:%02d".format(secs / 60, secs % 60)
        }
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Bye bye!"
        else -> "Ringing…"
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            heading.uppercase(),
            fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .padding(top = 12.dp)
                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center) {
            if (waiting) PulseRings(150.dp)
            Avatar(call.name ?: "", call.emoji, 150.dp, Color.White.copy(alpha = 0.18f))
        }
        Spacer(Modifier.height(28.dp))
        Text(
            title, fontSize = 42.sp, fontWeight = FontWeight.Black, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(status, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.75f))
        Spacer(Modifier.weight(1.2f))

        if (ringing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallButton(Icons.Rounded.CallEnd, "Decline", CallRed, onHangUp)
                CallButton(Icons.Rounded.Call, "Answer", CallGreen, onAnswer, bob = true)
            }
        } else {
            CallButton(
                Icons.AutoMirrored.Rounded.VolumeUp,
                if (call.speaker) "Speaker on" else "Speaker",
                if (call.speaker) Color.White else Color.White.copy(alpha = 0.16f),
                onSpeaker,
                size = 72.dp,
                iconTint = if (call.speaker) Ink else Color.White,
                glow = false,
            )
            Spacer(Modifier.height(28.dp))
            CallButton(Icons.Rounded.CallEnd, "Hang up", CallRed, onHangUp)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Two soft rings that keep spreading out from the picture while the phone rings. */
@Composable
private fun PulseRings(size: Dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    repeat(2) { i ->
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(2200, easing = LinearOutSlowInEasing),
                initialStartOffset = StartOffset(i * 1100),
            ),
            label = "ring$i",
        )
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = 1f + p * 0.7f
                    scaleY = 1f + p * 0.7f
                    alpha = (1f - p) * 0.45f
                }
                .background(Color.White, CircleShape)
        )
    }
}

@Composable
private fun CallButton(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    size: Dp = 88.dp,
    iconTint: Color = Color.White,
    bob: Boolean = false,
    glow: Boolean = true,
) {
    val scale = if (bob) {
        val t = rememberInfiniteTransition(label = "bob")
        val s by t.animateFloat(1f, 1.08f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "bobScale")
        s
    } else 1f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .size(size)
                .then(if (glow) Modifier.shadow(18.dp, CircleShape, ambientColor = color, spotColor = color) else Modifier)
                .bounceClick(onClick = onClick)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(size * 0.44f))
        }
        Spacer(Modifier.height(10.dp))
        Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
