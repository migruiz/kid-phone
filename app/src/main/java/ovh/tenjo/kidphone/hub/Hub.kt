package ovh.tenjo.kidphone.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import ovh.tenjo.kidphone.data.Store
import ovh.tenjo.kidphone.kiosk.Kiosk
import ovh.tenjo.kidphone.shared.Http
import ovh.tenjo.kidphone.shared.HttpError

/** Whether this phone can reach the home hub, which means it is on the home Wi-Fi. */
enum class HomeStatus {
    /** Not connected to a hub yet (set up in the parent menu). */
    NotSetUp,
    /** Just started or just woke up; no answer yet. */
    Checking,
    AtHome,
    /** The hub doesn't answer: not on the home Wi-Fi (or the Pi is off). */
    Away,
}

/**
 * The link to the home hub on the Raspberry Pi. The hub is only reachable on the home network, so
 * reaching it is how the phone knows it is at home. Parents' phones talk to the same hub from
 * anywhere; see hub/API.md.
 *
 * While the screen is on, this keeps one request open to the hub, which answers as soon as the
 * parents change something (or after 25 seconds), and sends the list of apps whenever it changes.
 */
object Hub {
    private const val TAG = "KidPhoneHub"
    const val DEFAULT_ADDRESS = "192.168.0.11:8774"

    /** How long the phone may be away from the home Wi-Fi before the allowed apps close. */
    private const val AWAY_GRACE_MS = 60_000L

    private const val PREFS = "hub"
    private const val KEY_ADDRESS = "address"
    private const val KEY_TOKEN = "token"
    private const val KEY_ALLOWED = "allowed"
    private const val KEY_APPS_SENT = "appsSent"
    private const val KEY_ICONS_SENT = "iconsSent"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _status = MutableStateFlow(HomeStatus.NotSetUp)
    val status: StateFlow<HomeStatus> = _status

    /** The apps the parents allow (installed or not), as last heard from the hub. */
    private val _allowed = MutableStateFlow<Set<String>>(emptySet())
    val allowed: StateFlow<Set<String>> = _allowed

    /**
     * True while allowed apps may open: on the home Wi-Fi, or only just left it. Off for
     * [AWAY_GRACE_MS] away from home, then any open app closes.
     */
    private val _appsAvailable = MutableStateFlow(false)
    val appsAvailable: StateFlow<Boolean> = _appsAvailable

    /** The hub's state version last seen, for the long-poll. -1 asks for an answer at once. */
    private var version = -1L

    val address: String get() = prefs.getString(KEY_ADDRESS, null) ?: DEFAULT_ADDRESS
    private val token: String? get() = prefs.getString(KEY_TOKEN, null)
    val isSetUp: Boolean get() = token != null

    private fun url(path: String) = "http://$address$path"

    /** Joins the hub at [address] as the child's phone. Throws with a readable message on failure. */
    suspend fun connect(address: String, childName: String) = withContext(Dispatchers.IO) {
        val clean = address.trim().removePrefix("http://").trimEnd('/').ifBlank { DEFAULT_ADDRESS }
        val body = JSONObject().put("childName", childName)
        // Re-joining the same hub keeps working without a parent's go-ahead.
        if (clean == this@Hub.address) token?.let { body.put("token", it) }
        val answer = try {
            Http.post("http://$clean/kid/register", body, timeoutMs = 8_000)
        } catch (e: HttpError) {
            throw IllegalStateException(
                if (e.code == "already_registered") {
                    "This hub already has a child's phone. In Kid Phone Parent, tap \"Replace the child's phone\" first."
                } else {
                    e.message ?: "The hub said no"
                }
            )
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Can't reach the hub at $clean. Is the phone on the home Wi-Fi?")
        }
        prefs.edit()
            .putString(KEY_ADDRESS, clean)
            .putString(KEY_TOKEN, answer.getString("token"))
            .remove(KEY_APPS_SENT) // a new hub needs the whole list, with icons
            .remove(KEY_ICONS_SENT)
            .apply()
        version = -1
        _status.value = HomeStatus.Checking
        wake.trySend(Unit)
    }

    /** Stops using the hub. */
    fun forget() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_APPS_SENT).remove(KEY_ICONS_SENT).apply()
        _status.value = HomeStatus.NotSetUp
        wake.trySend(Unit)
    }

    /** A one-time code a parent types into Kid Phone Parent to pair their phone. */
    suspend fun pairingCode(): String = withContext(Dispatchers.IO) {
        Http.post(url("/kid/pairing-code"), token = token, timeoutMs = 8_000).getString("code")
    }

    /** Something changed on the phone (an app installed or removed): tell the hub soon. */
    fun poke() {
        wake.trySend(Unit)
    }

    /** Starts the link. Called once when the app starts. */
    fun watch(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        _allowed.value = prefs.getStringSet(KEY_ALLOWED, emptySet()).orEmpty().toSet()
        _status.value = if (isSetUp) HomeStatus.Checking else HomeStatus.NotSetUp
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON && isSetUp) _status.value = HomeStatus.Checking
                wake.trySend(Unit)
            }
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { wake.trySend(Unit) }
                override fun onLost(network: Network) { wake.trySend(Unit) }
            }
        )

        // Apps may open at home, and for a minute after leaving.
        scope.launch {
            _status.collectLatest { s ->
                when (s) {
                    HomeStatus.AtHome -> _appsAvailable.value = true
                    HomeStatus.NotSetUp -> _appsAvailable.value = false
                    HomeStatus.Away -> { delay(AWAY_GRACE_MS); _appsAvailable.value = false }
                    HomeStatus.Checking -> Unit // keep what it was while the phone wakes up
                }
            }
        }
        // Only the apps that may open now can run in kid mode.
        scope.launch {
            combine(_allowed, _appsAvailable) { allowed, available -> if (available) allowed else emptySet() }
                .distinctUntilChanged()
                .collect { Kiosk.setAllowedApps(app, it) }
        }

        val power = app.getSystemService(PowerManager::class.java)
        scope.launch {
            var failures = 0
            while (isActive) {
                if (!isSetUp || !power.isInteractive) {
                    withTimeoutOrNull(5 * 60_000L) { wake.receive() }
                    continue
                }
                runCatching { reportAppsIfChanged() }.onFailure { Log.w(TAG, "Couldn't send the app list: ${it.message}") }
                val started = SystemClock.elapsedRealtime()
                val ok = fetchState()
                if (!isSetUp) continue // the hub forgot this phone
                if (ok) {
                    failures = 0
                    _status.value = HomeStatus.AtHome
                    // The long-poll paces the loop; only a quick answer needs a short pause.
                    if (SystemClock.elapsedRealtime() - started < 1_000) delay(1_000)
                } else {
                    failures++
                    // One missed answer right after waking is normal (Wi-Fi reconnecting).
                    if (failures >= 2) _status.value = HomeStatus.Away
                    withTimeoutOrNull(8_000L) { wake.receive() }
                }
            }
        }
    }

    /** Asks the hub for the allowed apps; waits up to 25 seconds for a change. False if unreachable. */
    private fun fetchState(): Boolean = try {
        val json = Http.get(url("/kid/state?since=$version&wait=25"), token, timeoutMs = 35_000)
        version = json.optLong("version", -1)
        val allowed = json.optJSONArray("allowedPackages").strings().toSet()
        if (allowed != _allowed.value) {
            _allowed.value = allowed
            prefs.edit().putStringSet(KEY_ALLOWED, allowed).apply()
        }
        true
    } catch (e: HttpError) {
        if (e.status == 401) {
            Log.w(TAG, "The hub no longer knows this phone")
            forget()
        }
        false
    } catch (e: Exception) {
        false
    }

    /**
     * Sends the app list when it differs from what was last sent. Icons go only for apps that are
     * new or updated since, so most updates are small.
     */
    private fun reportAppsIfChanged() {
        val apps = Apps.launchable(app)
        val childName = Store.get(app).childName.value
        val fingerprint = (listOf(childName) + apps.map { "${it.packageName}:${it.label}:${it.updatedAt}" })
            .joinToString("|").hashCode().toString()
        if (fingerprint == prefs.getString(KEY_APPS_SENT, null)) return
        val iconsSent = prefs.getStringSet(KEY_ICONS_SENT, emptySet()).orEmpty()
        val iconsNow = mutableSetOf<String>()
        val list = JSONArray()
        apps.forEach { a ->
            val key = "${a.packageName}@${a.updatedAt}"
            val item = JSONObject().put("package", a.packageName).put("label", a.label)
            if (key !in iconsSent) Apps.iconPng(app, a.packageName)?.let { item.put("iconPng", it) }
            iconsNow += key
            list.put(item)
        }
        Http.put(url("/kid/apps"), JSONObject().put("childName", childName).put("apps", list), token, timeoutMs = 30_000)
        prefs.edit().putString(KEY_APPS_SENT, fingerprint).putStringSet(KEY_ICONS_SENT, iconsNow).apply()
        Log.i(TAG, "Sent ${apps.size} apps to the hub")
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
