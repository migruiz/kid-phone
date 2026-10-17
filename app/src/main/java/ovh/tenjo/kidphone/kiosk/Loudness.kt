package ovh.tenjo.kidphone.kiosk

import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Makes sure the kid never misses a call: in kid mode the phone rings at full volume and vibrates,
 * even if it had been put on silent or Do Not Disturb.
 */
object Loudness {
    private const val TAG = "KidPhone"

    /** Silent and Do Not Disturb off; ring and call volume to 100%. */
    fun makeLoud(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val notifications = context.getSystemService(NotificationManager::class.java)

        // Leaving Do Not Disturb or silent needs "Do Not Disturb access", which a device-owner
        // app has (and which setup also grants from the computer, to be sure).
        step("Do Not Disturb off") {
            if (notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }
        step("silent off") {
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        }
        step("unmute") {
            if (Kiosk.isDeviceOwner(context)) {
                context.getSystemService(DevicePolicyManager::class.java).setMasterVolumeMuted(Kiosk.admin(context), false)
            }
        }
        for (stream in listOf(AudioManager.STREAM_RING, AudioManager.STREAM_VOICE_CALL)) {
            step("volume $stream") { audio.setStreamVolume(stream, audio.getStreamMaxVolume(stream), 0) }
        }
    }

    // ---- Vibrating while a call rings ----

    /**
     * The vibrator that is vibrating right now. Android only lets the same vibrator object that
     * started a vibration cancel it, so it is kept until [stopVibrating].
     */
    private var active: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val safetyStop = Runnable { cancelActive() }

    /** Never vibrate longer than this, whatever happens to the call. */
    private const val MAX_VIBRATION_MS = 60_000L

    private fun vibrator(context: Context): Vibrator? {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)
    }

    /**
     * Vibrates in a ring-like rhythm until [stopVibrating], for at most a minute. Uses the
     * "alarm" kind of vibration, which Android doesn't turn off in silent mode or when
     * "vibrate for calls" is off.
     */
    fun startVibrating(context: Context) {
        if (active != null) return
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        val rhythm = VibrationEffect.createWaveform(longArrayOf(0, 900, 700), 0)
        val kind = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        step("vibrate") {
            @Suppress("DEPRECATION")
            vibrator.vibrate(rhythm, kind)
            active = vibrator
            handler.removeCallbacks(safetyStop)
            handler.postDelayed(safetyStop, MAX_VIBRATION_MS)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun stopVibrating(context: Context) = cancelActive()

    private fun cancelActive() {
        handler.removeCallbacks(safetyStop)
        val vibrator = active ?: return
        active = null
        step("stop vibrating") { vibrator.cancel() }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Loudness step '$name' failed", e)
        }
    }
}
