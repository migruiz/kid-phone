package ovh.tenjo.kidphone.hub

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ovh.tenjo.kidphone.shared.HttpError
import ovh.tenjo.kidphone.ui.BatteryInfo
import ovh.tenjo.kidphone.ui.currentBattery
import ovh.tenjo.kidphone.ui.parseBattery
import java.util.concurrent.TimeUnit

/**
 * Tells the home hub the battery level and whether the phone is plugged in. The hub alerts the
 * parents below 25%, 15% and 10% (see hub/API.md). The hub can only be reached on the home Wi-Fi,
 * so away from home nothing goes out until the phone is back.
 *
 * A report goes whenever the level or the plug changes, with the screen on or off. Reports go
 * through WorkManager, so they still go out while the phone sleeps, and are retried when the hub
 * can't be reached. A check every 30 minutes catches anything missed. With the screen off they go
 * out at once only if a parent has let Kid Phone run in the background ([goesOutAtOnce]).
 */
object BatteryReport {
    private const val TAG = "KidPhoneBattery"
    private const val WORK_SEND = "battery-report"
    private const val WORK_CHECK = "battery-check"
    private const val PREFS = "battery"
    /** The reading the hub last accepted, as "percent|charging". */
    private const val KEY_SENT = "sent"

    /** The reading last handed to WorkManager, so the frequent battery broadcasts don't queue it again. */
    @Volatile private var queued: String? = null

    private fun BatteryInfo.key() = "$percent|$charging"
    private fun sent(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SENT, null)

    /** Starts watching the battery. Called once when the app starts, after Hub.watch. */
    fun start(context: Context) {
        val app = context.applicationContext
        // Exported: only the system sends battery broadcasts, and on Android 12 and older a "not
        // exported" receiver doesn't get them. Registering also delivers the current reading.
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = changed(app, parseBattery(intent))
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_CHECK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BatteryWorker>(30, TimeUnit.MINUTES).setConstraints(online).build(),
        )
    }

    /**
     * Whether Android lets the reports out at once. If not, with the screen off it holds them back:
     * for hours in deep sleep, and altogether while battery saver is on, until the screen comes on.
     */
    fun goesOutAtOnce(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Android's own "let it run in the background?" question, for a parent to answer once. */
    @SuppressLint("BatteryLife") // sideloaded, never on Google Play; the alerts need it
    fun goOutAtOnceRequest(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** Connected to a hub (maybe a new one): it gets the reading afresh. */
    fun forgetSent(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SENT).apply()
        queued = null
    }

    /** The phone is back on the home Wi-Fi: send what the hub missed now, without waiting for a retry. */
    fun backHome(context: Context) {
        val now = currentBattery(context)
        if (now.percent >= 0 && now.key() != sent(context)) sendSoon(context)
    }

    private fun changed(context: Context, now: BatteryInfo) {
        if (now.percent < 0 || !Hub.isSetUp) return
        val key = now.key()
        if (key == queued || key == sent(context)) return
        queued = key
        sendSoon(context)
    }

    // Any network: the hub only answers on the home Wi-Fi, but "unmetered" would miss a home Wi-Fi
    // that Android thinks is metered. Elsewhere the report fails quickly and waits for a retry.
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Sends the current reading as soon as there is a network; it replaces any report still waiting. */
    private fun sendSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_SEND, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BatteryWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** Sends the reading as it is now, if the hub hasn't got it yet. False: try again later. */
    internal suspend fun send(context: Context): Boolean {
        val now = currentBattery(context)
        if (now.percent < 0 || !Hub.isSetUp || now.key() == sent(context)) return true
        return try {
            Hub.reportBattery(now.percent, now.charging)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SENT, now.key()).apply()
            Log.i(TAG, "Battery ${now.percent}%${if (now.charging) ", charging" else ""} sent to the hub")
            true
        } catch (e: HttpError) {
            // The hub refused it (an older hub without battery alerts, or it no longer knows this
            // phone). Retrying won't help; the next change or check tries again.
            Log.w(TAG, "The hub refused the battery report: ${e.message}")
            true
        } catch (e: java.io.IOException) {
            false // not on the home Wi-Fi
        }
    }
}

/** Sends the battery reading to the hub (see [BatteryReport]). */
class BatteryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (BatteryReport.send(applicationContext)) Result.success() else Result.retry()
}
