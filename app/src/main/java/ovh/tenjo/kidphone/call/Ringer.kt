package ovh.tenjo.kidphone.call

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import ovh.tenjo.kidphone.data.Store

/** One of the phone's ringtones. An empty [uri] means the phone's own ringtone setting. */
data class RingtoneChoice(val title: String, val uri: String)

/**
 * The phone's ringtones, and playing the right one when someone rings.
 *
 * Kid Phone plays the ringtone itself (IN_CALL_SERVICE_RINGING in the manifest), so each call
 * button can have its own; everyone else gets the phone's ringtone. Android then leaves both the
 * sound and the vibration to us, so outside kid mode we also vibrate when the phone's settings say
 * so. In kid mode the volume is 100% and it always vibrates (see CallService).
 */
object Ringer {
    private const val TAG = "KidPhone"

    /** Never ring longer than this, whatever happens to the call. */
    private const val MAX_RING_MS = 120_000L

    const val PHONE_DEFAULT_TITLE = "Phone's ringtone"

    private val ringing = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var playing: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())
    private val safetyStop = Runnable { stop() }

    /** Rings for a call from [number]: their button's ringtone, or the phone's. */
    fun start(context: Context, number: String?) {
        stop()
        val app = context.applicationContext
        val chosen = Store.get(app).button(number)?.ringtone.orEmpty()
        val ringtone = open(app, chosen) ?: open(app, "") ?: run {
            Log.w(TAG, "No ringtone to play")
            return
        }
        try {
            ringtone.isLooping = true
            ringtone.play()
            playing = ringtone
            handler.postDelayed(safetyStop, MAX_RING_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not play the ringtone", e)
        }
    }

    fun stop() {
        handler.removeCallbacks(safetyStop)
        val ringtone = playing ?: return
        playing = null
        runCatching { ringtone.stop() }
    }

    /**
     * A ringtone ready to play with the ringing volume, or null if [uri] no longer exists on the
     * phone (an update can remove ringtones). An empty [uri] is the phone's own ringtone.
     */
    fun open(context: Context, uri: String): Ringtone? {
        val address = if (uri.isBlank()) Settings.System.DEFAULT_RINGTONE_URI else Uri.parse(uri)
        if (uri.isNotBlank()) {
            val exists = runCatching {
                context.contentResolver.openAssetFileDescriptor(address, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (!exists) return null
        }
        return runCatching {
            RingtoneManager.getRingtone(context, address)?.apply { audioAttributes = ringing }
        }.getOrNull()
    }

    /** The phone's built-in ringtones, by name, after "Phone's ringtone". Slow: not on the main thread. */
    fun choices(context: Context): List<RingtoneChoice> {
        val found = runCatching {
            val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_RINGTONE) }
            val cursor = manager.cursor
            buildList {
                while (cursor.moveToNext()) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX).orEmpty()
                    val uri = manager.getRingtoneUri(cursor.position)?.toString()
                    if (title.isNotBlank() && uri != null) add(RingtoneChoice(title, uri))
                }
            }
        }.onFailure { Log.w(TAG, "Could not list the ringtones", it) }.getOrDefault(emptyList())
        return listOf(RingtoneChoice(PHONE_DEFAULT_TITLE, "")) +
            found.distinctBy { it.uri }.sortedBy { it.title.lowercase() }
    }

    /** The name of a ringtone, for the call button editor. */
    fun title(context: Context, uri: String): String {
        if (uri.isBlank()) return PHONE_DEFAULT_TITLE
        val title = runCatching {
            context.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.TITLE), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        return title?.ifBlank { null } ?: "$PHONE_DEFAULT_TITLE (the chosen one is gone)"
    }

    /**
     * Outside kid mode: whether the phone's own settings want an incoming call to vibrate. Silent
     * mode and Do Not Disturb mean no; vibrate mode means yes; otherwise "vibrate for calls".
     */
    fun phoneWantsVibration(context: Context): Boolean {
        val dnd = runCatching {
            context.getSystemService(NotificationManager::class.java).currentInterruptionFilter >
                NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)
        if (dnd) return false
        return when (context.getSystemService(AudioManager::class.java)?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> false
            AudioManager.RINGER_MODE_VIBRATE -> true
            else -> Settings.System.getInt(context.contentResolver, "vibrate_when_ringing", 0) == 1
        }
    }
}
