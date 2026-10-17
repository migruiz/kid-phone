package ovh.tenjo.kidphone.call

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * The "ring... ring..." sound while a call goes out and the other phone is ringing, so the child
 * hears that something is happening. Played on the call's own audio (earpiece, or the speaker
 * when it is on) and stopped as soon as the call is answered or ends.
 */
object Ringback {
    private var tone: ToneGenerator? = null

    fun start() {
        if (tone != null) return
        tone = try {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80).also {
                it.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        } catch (e: RuntimeException) {
            Log.w("KidPhone", "Could not play the ringing sound", e)
            null
        }
    }

    fun stop() {
        val t = tone ?: return
        tone = null
        runCatching {
            t.stopTone()
            t.release()
        }
    }
}
