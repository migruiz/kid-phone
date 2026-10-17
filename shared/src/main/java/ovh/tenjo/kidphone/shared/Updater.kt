package ovh.tenjo.kidphone.shared

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** What the self-updater is doing, for the "Updates" line in each app. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(val version: String) : UpdateState
    /** Downloaded; waiting for a good moment to install (Kid Phone: screen off, no call). */
    data class Waiting(val version: String) : UpdateState
    data class Installing(val version: String) : UpdateState
    /** Android wants someone to tap "Update" (the parent app's first self-update). */
    data class NeedsTap(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Keeps the app up to date from the GitHub releases of github.com/migruiz/kid-phone.
 *
 * Every release carries both apps (kid-phone.apk, kid-phone-parent.apk) and is tagged with the
 * shared version, e.g. v1.2.3. An app updates when the latest release's version is higher than
 * its own. Kid Phone is the phone's device owner, so Android installs its updates without asking.
 * The parent app asks once ("Update?"); from Android 12 on, later updates install by themselves.
 */
object Updater {
    private const val TAG = "KidPhoneUpdate"
    private const val REPO = "migruiz/kid-phone"
    private const val WORK_NAME = "self-update"
    private const val CHANNEL = "updates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private lateinit var assetName: String
    private var canInstallNow: (Context) -> Boolean = { true }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private data class Release(val tag: String, val versionCode: Long, val apkUrl: String, val size: Long)

    /**
     * Called once when the app starts. [assetName] is this app's file in each release.
     * [canInstallNow] says whether an update may install right now; a parent's "Check for
     * updates" tap ignores it.
     */
    fun configure(context: Context, assetName: String, canInstallNow: (Context) -> Boolean = { true }) {
        this.assetName = assetName
        this.canInstallNow = canInstallNow
        Http.appVersion = currentVersionCode(context)
    }

    /** Checks every [hours] hours in the background, on Wi-Fi only when [wifiOnly]. */
    fun schedule(context: Context, hours: Long, wifiOnly: Boolean) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** A parent tapped "Check for updates": check now and install straight away if there is one. */
    fun checkNow(context: Context) {
        val app = context.applicationContext
        scope.launch { run(app, force = true) }
    }

    /** Installs an update that was downloaded earlier, if now is a good moment. */
    fun installDownloadedIfAllowed(context: Context) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                val file = downloadedUpdate(app) ?: return@withLock
                if (canInstallNow(app)) install(app, file, versionNameOf(file, app))
            }
        }
    }

    fun currentVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** "v1.2.3" → 10203, the same rule the build uses. Null for anything else. */
    fun versionCode(tag: String): Long? {
        val parts = tag.trim().removePrefix("v").split(".")
        if (parts.size != 3) return null
        val (major, minor, patch) = parts.map { it.toIntOrNull() ?: return null }
        if (minor !in 0..99 || patch !in 0..99) return null
        return major * 10000L + minor * 100L + patch
    }

    /** One check: download a newer release if there is one, then install it if allowed. */
    suspend fun run(context: Context, force: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                _state.value = UpdateState.Checking
                val current = currentVersionCode(context)
                val release = latestRelease()
                if (release == null || release.versionCode <= current) {
                    cleanUp(context, keep = null)
                    _state.value = UpdateState.UpToDate
                    return@withLock
                }
                val version = release.tag.removePrefix("v")
                val file = download(context, release, version)
                if (force || canInstallNow(context)) {
                    install(context, file, version)
                } else {
                    Log.i(TAG, "Update $version downloaded, waiting for a good moment")
                    _state.value = UpdateState.Waiting(version)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun latestRelease(): Release? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "kid-phone-updater")
            if (conn.responseCode == 404) return null // no release yet
            if (conn.responseCode != 200) throw HttpError(conn.responseCode, null, "GitHub said ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val code = versionCode(tag) ?: return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == assetName) {
                    return Release(tag, code, a.getString("browser_download_url"), a.optLong("size", -1))
                }
            }
            Log.w(TAG, "Release $tag has no $assetName")
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun updatesDir(context: Context) = File(context.filesDir, "updates").apply { mkdirs() }

    private fun download(context: Context, release: Release, version: String): File {
        val target = File(updatesDir(context), "${release.versionCode}.apk")
        if (target.exists() && (release.size < 0 || target.length() == release.size) && isValidUpdate(context, target)) {
            return target
        }
        _state.value = UpdateState.Downloading(version)
        cleanUp(context, keep = null)
        val partial = File(target.path + ".part")
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "kid-phone-updater")
            if (conn.responseCode != 200) throw HttpError(conn.responseCode, null, "Download failed (${conn.responseCode})")
            conn.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        if (!partial.renameTo(target)) throw IllegalStateException("Could not save the update")
        if (!isValidUpdate(context, target)) {
            target.delete()
            throw IllegalStateException("The downloaded file is not a newer version of this app")
        }
        return target
    }

    /** The file is this same app with a higher version. (Android itself checks the signature.) */
    private fun isValidUpdate(context: Context, file: File): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: return false
        return info.packageName == context.packageName && info.longVersionCode > currentVersionCode(context)
    }

    private fun downloadedUpdate(context: Context): File? =
        updatesDir(context).listFiles { f -> f.name.endsWith(".apk") }
            ?.maxByOrNull { it.name.removeSuffix(".apk").toLongOrNull() ?: 0 }
            ?.takeIf { isValidUpdate(context, it) }

    private fun versionNameOf(file: File, context: Context): String =
        context.packageManager.getPackageArchiveInfo(file.path, 0)?.versionName ?: "?"

    private fun cleanUp(context: Context, keep: File?) {
        updatesDir(context).listFiles()?.forEach { if (it != keep) it.delete() }
    }

    private fun install(context: Context, file: File, version: String) {
        Log.i(TAG, "Installing update $version")
        _state.value = UpdateState.Installing(version)
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context, id,
                Intent(context, UpdateReceiver::class.java).putExtra(UpdateReceiver.EXTRA_VERSION, version),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    // ---- Called by UpdateReceiver ----

    internal fun onInstallFailed(message: String) {
        Log.w(TAG, "Update install failed: $message")
        _state.value = UpdateState.Failed(message)
    }

    /** Android wants a tap. Open its "Update?" screen now if we can, and leave a notification too. */
    internal fun onNeedsTap(context: Context, confirm: Intent, version: String) {
        _state.value = UpdateState.NeedsTap(version)
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(confirm) }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label = context.applicationInfo.loadLabel(context.packageManager)
        nm.notify(
            CHANNEL.hashCode(),
            android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("$label $version is ready")
                .setContentText("Tap to install the update.")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }
}

/** The background check WorkManager runs every few hours. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Updater.run(applicationContext, force = false)
        return Result.success()
    }
}
