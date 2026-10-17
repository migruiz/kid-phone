package ovh.tenjo.kidphone.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import ovh.tenjo.kidphone.R
import ovh.tenjo.kidphone.data.Store
import ovh.tenjo.kidphone.kiosk.Loudness

/**
 * Android hands every call to this service because this app is the phone's calling app. It shows
 * our own call screen instead of the phone's normal one.
 */
class CallService : InCallService() {

    override fun onCallAdded(call: Call) {
        CallManager.service = this
        val ringing = CallManager.stateOf(call) == Call.STATE_RINGING
        if (ringing) {
            val store = Store.get(this)
            val number = CallManager.numberOf(call)
            // Second line of defence: the screening service normally turns these away first.
            if (store.locked && !store.isAllowedCaller(number)) {
                Log.i(TAG, "Rejected call from ${number ?: "withheld number"}")
                reject(call)
                return
            }
            // Keep it simple: no call waiting. A second caller gets the busy signal.
            if (CallManager.hasCalls) {
                reject(call)
                return
            }
        }
        CallManager.add(this, call)

        // Kid mode: full volume and vibration for every allowed caller, whatever the phone's
        // sound settings. Vibration stops once the call is answered, declined or ends.
        if (ringing && Store.get(this).locked) {
            Loudness.makeLoud(this)
            Loudness.startVibrating(this)
        }

        val screen = Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ringing) postIncomingNotification(screen)
        runCatching { startActivity(screen) }.onFailure { Log.e(TAG, "Could not open call screen", it) }
    }

    override fun onCallRemoved(call: Call) {
        CallManager.remove(call)
    }

    @Deprecated("Still delivered on every supported version")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.onAudioState(audioState)
    }

    override fun onDestroy() {
        if (CallManager.service === this) CallManager.service = null
        super.onDestroy()
    }

    private fun reject(call: Call) {
        if (Build.VERSION.SDK_INT >= 30) call.reject(Call.REJECT_REASON_UNWANTED) else call.reject(false, null)
    }

    /** Wakes the screen with the call screen if the phone is asleep, or unlocked and in use. */
    private fun postIncomingNotification(screen: Intent) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the phone plays the ringtone itself
            }
        )
        val pending = PendingIntent.getActivity(
            this, 0, screen, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Incoming call")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        private const val TAG = "KidPhone"
        private const val CHANNEL = "incoming_calls"
        private const val NOTIFICATION_ID = 1

        fun cancelIncomingNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }
}
