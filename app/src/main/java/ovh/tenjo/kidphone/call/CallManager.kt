package ovh.tenjo.kidphone.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ovh.tenjo.kidphone.data.Contact
import ovh.tenjo.kidphone.data.PhoneNumbers
import ovh.tenjo.kidphone.data.Store
import ovh.tenjo.kidphone.kiosk.Loudness

/** What the call screen shows. */
data class CallInfo(
    val state: Int,
    val number: String,
    val name: String?,
    /** The caller's button colour, or -1 when they have no button. */
    val color: Int,
    val emoji: String,
    val incoming: Boolean,
    val connectedAt: Long?,
    val speaker: Boolean,
    /** Hang up was tapped while the call was still being set up; it ends as soon as it can. */
    val hangingUp: Boolean = false,
)

/** Keeps track of the call in progress and carries out the call screen's buttons. */
object CallManager {
    private const val TAG = "KidPhone"

    /** A call going out before it is dialling: picking a SIM, or being set up. */
    private val SETTING_UP = setOf(Call.STATE_NEW, Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_CONNECTING)
    private val ENDING = setOf(Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED)
    private const val SETTING_UP_GRACE_MS = 5_000L

    private val calls = mutableListOf<Call>()

    /** The button last tapped on the kid screen, so its call shows the right person. */
    private var lastTapped: Contact? = null

    /** When the last call from a button started, to ignore the second tap of a double tap. */
    private var lastPlacedAt = 0L

    fun placeCall(context: Context, contact: Contact): PlaceResult {
        // A call is already going, or one is just starting: show it rather than start another.
        if (hasLiveCall || SystemClock.elapsedRealtime() - lastPlacedAt < 2_000) {
            if (hasLiveCall) showCallScreen(context)
            return PlaceResult.Ok
        }
        lastTapped = contact
        return placeCall(context, contact.number).also {
            if (it == PlaceResult.Ok) lastPlacedAt = SystemClock.elapsedRealtime()
        }
    }
    private var speaker = false
    internal var service: InCallService? = null
    private var appContext: Context? = null

    private val _current = MutableStateFlow<CallInfo?>(null)
    val current: StateFlow<CallInfo?> = _current

    /** A call whose Hang up was tapped before it was dialling (see [hangUp]). */
    private var hangUpWhenDialing: Call? = null
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_SELECT_PHONE_ACCOUNT) pickSim(call)
            if (call === hangUpWhenDialing && state !in SETTING_UP) {
                hangUpWhenDialing = null
                if (state != Call.STATE_DISCONNECTED) call.disconnect()
            }
            publish()
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
    }

    val hasCalls: Boolean get() = calls.isNotEmpty()

    /**
     * A call is ringing, going out or going on (not one that is only ending). Kept up to date by
     * [publish], so it can be read from any thread.
     */
    @Volatile
    var hasLiveCall: Boolean = false
        private set

    /** Opens the call screen, or brings it back to the front. */
    fun showCallScreen(context: Context) {
        val screen = Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(screen) }.onFailure { Log.e(TAG, "Could not open call screen", it) }
    }

    fun stateOf(call: Call): Int =
        if (Build.VERSION.SDK_INT >= 31) call.details.state else @Suppress("DEPRECATION") call.state

    fun numberOf(call: Call): String? = call.details.handle?.schemeSpecificPart

    internal fun add(context: Context, call: Call) {
        appContext = context.applicationContext
        calls += call
        call.registerCallback(callback)
        if (stateOf(call) == Call.STATE_SELECT_PHONE_ACCOUNT) pickSim(call)
        publish()
    }

    internal fun remove(call: Call) {
        call.unregisterCallback(callback)
        calls -= call
        if (hangUpWhenDialing === call) hangUpWhenDialing = null
        if (calls.isEmpty()) speaker = false
        publish()
    }

    /**
     * Android stopped using our call service. Any call still listed is no longer ours to show,
     * and keeping it would block every later caller and could leave a sound playing.
     */
    internal fun forgetAll() {
        if (calls.isEmpty()) return
        Log.w(TAG, "Call service closed with ${calls.size} call(s) still listed; forgetting them")
        calls.forEach { it.unregisterCallback(callback) }
        calls.clear()
        hangUpWhenDialing = null
        speaker = false
        publish()
    }

    internal fun onAudioState(state: CallAudioState?) {
        speaker = state?.route == CallAudioState.ROUTE_SPEAKER
        publish()
    }

    /** The call the screen is about: a ringing one first, otherwise the latest. */
    private fun primary(): Call? =
        calls.firstOrNull { stateOf(it) == Call.STATE_RINGING } ?: calls.lastOrNull()

    private fun publish() {
        hasLiveCall = calls.any { stateOf(it) !in ENDING }
        val call = primary()
        if (call == null) {
            _current.value = null
            appContext?.let { Loudness.stopVibrating(it) }
            Ringer.stop()
            Ringback.stop()
            return
        }
        // Ringing sound while our call goes out, until it is answered or ends.
        val state = stateOf(call)
        val hangingUp = call === hangUpWhenDialing
        val goingOut = call.details.callDirection == Call.Details.DIRECTION_OUTGOING && !hangingUp &&
            (state == Call.STATE_DIALING || state == Call.STATE_CONNECTING)
        if (goingOut) Ringback.start() else Ringback.stop()
        val number = numberOf(call).orEmpty()
        val outgoing = call.details.callDirection == Call.Details.DIRECTION_OUTGOING
        // For a call from a button, show that button's person, even if two buttons share a number.
        val who = lastTapped?.takeIf { outgoing && PhoneNumbers.same(it.number, number) }
            ?: appContext?.let { Store.get(it).lookup(number) }
        _current.value = CallInfo(
            state = stateOf(call),
            number = number,
            name = who?.name?.ifBlank { null },
            color = who?.color ?: -1,
            emoji = who?.emoji.orEmpty(),
            incoming = call.details.callDirection == Call.Details.DIRECTION_INCOMING,
            connectedAt = call.details.connectTimeMillis.takeIf { it > 0 },
            speaker = speaker,
            hangingUp = hangingUp,
        )
        if (calls.none { stateOf(it) == Call.STATE_RINGING }) {
            Ringer.stop()
            appContext?.let {
                CallService.cancelIncomingNotification(it)
                Loudness.stopVibrating(it)
            }
        }
    }

    fun answer() {
        primary()?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    /**
     * Ends the call, or turns down a ringing one. A call still being set up (the first moment
     * after tapping a button) is hung up as soon as it is dialling instead: ending it before
     * then can leave some phones dialling with no call left on screen, so nobody can stop it.
     */
    fun hangUp() {
        val call = primary() ?: return
        when (stateOf(call)) {
            Call.STATE_RINGING -> call.reject(false, null)
            in SETTING_UP -> {
                if (hangUpWhenDialing === call) return
                hangUpWhenDialing = call
                // If it never gets to dialling, end it anyway.
                handler.postDelayed({
                    if (hangUpWhenDialing === call) {
                        hangUpWhenDialing = null
                        call.disconnect()
                    }
                }, SETTING_UP_GRACE_MS)
                publish()
            }
            else -> call.disconnect()
        }
    }

    @Suppress("DEPRECATION") // setAudioRoute still works on every version this app supports
    fun setSpeaker(on: Boolean) {
        service?.setAudioRoute(
            if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
        )
    }

    /** Dual-SIM phones with no default SIM ask which SIM to use. A kid can't answer that. */
    private fun pickSim(call: Call) {
        val context = appContext ?: return
        val sim = runCatching {
            context.getSystemService(TelecomManager::class.java).callCapablePhoneAccounts.firstOrNull()
        }.getOrNull()
        if (sim != null) call.phoneAccountSelected(sim, false) else call.disconnect()
    }

    enum class PlaceResult { Ok, NoSim, Failed }

    /** Whether any SIM slot has a working SIM card. Without one only emergency calls work. */
    fun hasSim(context: Context): Boolean {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return false
        val slots = if (Build.VERSION.SDK_INT >= 30) tm.activeModemCount else @Suppress("DEPRECATION") tm.phoneCount
        return (0 until maxOf(slots, 1)).any { slot ->
            runCatching { tm.getSimState(slot) == TelephonyManager.SIM_STATE_READY }.getOrDefault(false)
        }
    }

    /**
     * Starts a call. Checks for a SIM first: without one Android would show an error message that
     * kid mode blocks, so the call would just silently do nothing.
     */
    fun placeCall(context: Context, number: String): PlaceResult {
        if (!hasSim(context)) return PlaceResult.NoSim
        val telecom = context.getSystemService(TelecomManager::class.java)
        return try {
            val extras = Bundle()
            runCatching { telecom.getDefaultOutgoingPhoneAccount("tel") }.getOrNull()?.let {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
            }
            telecom.placeCall(Uri.fromParts("tel", number, null), extras)
            PlaceResult.Ok
        } catch (e: SecurityException) {
            Log.e(TAG, "Not allowed to place a call", e)
            PlaceResult.Failed
        }
    }
}
