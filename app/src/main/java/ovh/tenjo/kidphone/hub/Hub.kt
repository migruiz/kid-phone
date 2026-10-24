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
import ovh.tenjo.kidphone.data.PhoneNumbers
import ovh.tenjo.kidphone.data.Store
import ovh.tenjo.kidphone.kiosk.Kiosk
import ovh.tenjo.kidphone.shared.Http
import ovh.tenjo.kidphone.shared.HttpError
import kotlin.math.abs

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
 * An "ask first" request as the child's phone sees it. [status] is pending, approved, rejected,
 * expired or cancelled. [endsAt] (wall clock) is when an approved call time runs out.
 */
data class KidRequest(val id: String, val number: String, val status: String, val minutes: Int?, val endsAt: Long?)

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
    private const val KEY_WINDOWS = "callWindows"
    private const val KEY_APP_ENDS = "appEnds"

    /** Two end times closer than this are the same one, heard again a little later. */
    private const val SAME_END_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _status = MutableStateFlow(HomeStatus.NotSetUp)
    val status: StateFlow<HomeStatus> = _status

    /**
     * The apps switched on (installed or not), as last heard from the hub, and when the time is
     * up for those switched on for a while (package → wall clock on this phone). Kept together so
     * both always change at once.
     */
    private data class Switches(val on: Set<String>, val ends: Map<String, Long>)
    private val switches = MutableStateFlow(Switches(emptySet(), emptyMap()))

    /** Goes up when the screen comes on, so time that passed in deep sleep is caught up at once. */
    private val wokeUp = MutableStateFlow(0)
    val screenOns: StateFlow<Int> = wokeUp

    /**
     * The apps he may open now: switched on, and with time left. An app's time running out takes
     * it off at once, on this phone's own clock, even away from the hub.
     */
    private val _allowed = MutableStateFlow<Set<String>>(emptySet())
    val allowed: StateFlow<Set<String>> = _allowed

    /** Of the apps he may open now, those with a time: package → when it is up (wall clock). */
    private val _appEnds = MutableStateFlow<Map<String, Long>>(emptyMap())
    val appEnds: StateFlow<Map<String, Long>> = _appEnds

    /**
     * True while allowed apps may open: on the home Wi-Fi, or only just left it. Off for
     * [AWAY_GRACE_MS] away from home, then any open app closes.
     */
    private val _appsAvailable = MutableStateFlow(false)
    val appsAvailable: StateFlow<Boolean> = _appsAvailable

    /** Recent "ask first" requests from the hub (pending, approved, just answered). */
    private val _requests = MutableStateFlow<List<KidRequest>>(emptyList())
    val requests: StateFlow<List<KidRequest>> = _requests

    /**
     * Approved call times: number → when it ends (wall clock). Kept on the phone, so a call time a
     * parent gave keeps working even if the phone leaves the home Wi-Fi.
     */
    private val _windows = MutableStateFlow<Map<String, Long>>(emptyMap())
    val windows: StateFlow<Map<String, Long>> = _windows

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
        BatteryReport.forgetSent(app)
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

    /** When the call time for this number ends, or null if it has none now. */
    fun windowEnd(number: String?): Long? {
        val now = System.currentTimeMillis()
        return _windows.value.entries.firstOrNull { it.value > now && PhoneNumbers.same(it.key, number) }?.value
    }

    /**
     * Whether the child may ring this number in kid mode: a call button, and for an "ask first"
     * button only during a call time a parent gave. Other apps could try to dial anyone; those
     * calls are hung up (see CallService).
     */
    fun mayCallNow(context: Context, number: String?): Boolean {
        val button = Store.get(context).button(number) ?: return false
        return !button.askFirst || windowEnd(number) != null
    }

    /** Asks the parents whether the child may ring [number]. Throws with a readable message. */
    suspend fun ask(contactName: String, number: String): KidRequest = withContext(Dispatchers.IO) {
        val answer = try {
            Http.post(url("/kid/requests"), JSONObject().put("contactName", contactName).put("number", number), token, timeoutMs = 8_000)
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Couldn't ask. Asking only works at home, on the home Wi-Fi.")
        }
        val request = parseRequest(answer, System.currentTimeMillis())
        merge(listOf(request))
        wake.trySend(Unit)
        request
    }

    /**
     * A parent switched an app on or off in Kid Phone's parent menu: on for [minutes] from now,
     * or with no time limit when null. Goes through the hub, so the parent app shows the same;
     * needs the home Wi-Fi. Throws with a readable message.
     */
    suspend fun setAllowed(packageName: String, allowed: Boolean, minutes: Int? = null) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("allowed", allowed)
        if (allowed && minutes != null) body.put("minutes", minutes)
        try {
            Http.put(url("/kid/apps/$packageName"), body, token, timeoutMs = 8_000)
        } catch (e: HttpError) {
            poke() // most likely an app the hub hasn't heard of yet
            throw IllegalStateException(
                if (e.status == 404) "The hub doesn't know this app yet. Try again in a minute." else (e.message ?: "The hub said no")
            )
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Couldn't reach the hub. Changing apps needs the home Wi-Fi.")
        }
        val s = switches.value
        setSwitches(
            on = if (allowed) s.on + packageName else s.on - packageName,
            ends = if (allowed && minutes != null) {
                s.ends + (packageName to System.currentTimeMillis() + minutes * 60_000L)
            } else {
                s.ends - packageName
            },
        )
        wake.trySend(Unit)
    }

    private fun setSwitches(on: Set<String>, ends: Map<String, Long>) {
        val fresh = Switches(on, ends.filterKeys { it in on })
        if (fresh == switches.value) return
        switches.value = fresh
        prefs.edit()
            .putStringSet(KEY_ALLOWED, fresh.on)
            .putString(KEY_APP_ENDS, JSONObject(fresh.ends as Map<*, *>).toString())
            .apply()
    }

    /** Tells the hub the battery level, so it can alert the parents when it runs low (see BatteryReport). */
    suspend fun reportBattery(percent: Int, charging: Boolean) = withContext(Dispatchers.IO) {
        Http.put(url("/kid/battery"), JSONObject().put("percent", percent).put("charging", charging), token, timeoutMs = 8_000)
        Unit
    }

    /** Stops waiting for an answer. */
    suspend fun cancel(requestId: String) = withContext(Dispatchers.IO) {
        runCatching { Http.post(url("/kid/requests/$requestId/cancel"), token = token, timeoutMs = 8_000) }
        Unit
    }

    private fun parseRequest(o: JSONObject, now: Long) = KidRequest(
        id = o.getString("id"),
        number = o.optString("number"),
        status = o.optString("status"),
        minutes = if (o.has("minutes") && !o.isNull("minutes")) o.getInt("minutes") else null,
        endsAt = if (o.has("remainingSeconds") && !o.isNull("remainingSeconds")) now + o.getLong("remainingSeconds") * 1000 else null,
    )

    /** Takes in requests from the hub: updates the list and the call times. */
    private fun merge(fresh: List<KidRequest>, replaceAll: Boolean = false) {
        _requests.value = if (replaceAll) fresh else (fresh + _requests.value.filter { old -> fresh.none { it.id == old.id } })
        val now = System.currentTimeMillis()
        val windows = _windows.value.filterValues { it > now }.toMutableMap()
        fresh.filter { it.status == "approved" && it.endsAt != null }.forEach { windows[it.number] = it.endsAt!! }
        // A parent stopped a call time early.
        fresh.filter { it.status == "ended" }.forEach { r -> windows.keys.removeAll { PhoneNumbers.same(it, r.number) } }
        saveWindows(windows)
    }

    private fun saveWindows(windows: Map<String, Long>) {
        if (windows == _windows.value) return
        _windows.value = windows
        prefs.edit().putString(KEY_WINDOWS, JSONObject(windows as Map<*, *>).toString()).apply()
    }

    /**
     * A parent with the child answers on this phone (after the PIN): 0 says no, otherwise the
     * minutes. Always through the hub, so the parents' phones show the same answer; needs the home
     * Wi-Fi. Returns the request as it now stands (the other parent may have answered first).
     * Throws with a readable message.
     */
    suspend fun answerHere(requestId: String, minutes: Int): Pair<KidRequest, Boolean> = withContext(Dispatchers.IO) {
        val json = hubCall { Http.post(url("/kid/requests/$requestId/answer"), JSONObject().put("minutes", minutes), token, timeoutMs = 8_000) }
        val r = parseRequest(json.getJSONObject("request"), System.currentTimeMillis())
        merge(listOf(r))
        wake.trySend(Unit)
        r to json.optBoolean("alreadyAnswered")
    }

    /**
     * A parent changes or stops a running call time on this phone (in the parent menu): 0 stops
     * it, otherwise that many minutes from now. Always through the hub, so every phone agrees;
     * needs the home Wi-Fi. Throws with a readable message.
     */
    suspend fun changeHere(number: String, minutes: Int) = withContext(Dispatchers.IO) {
        val id = _requests.value.firstOrNull { it.status == "approved" && PhoneNumbers.same(it.number, number) }?.id
            ?: throw IllegalStateException("Can't change it from here right now. Changing call times needs the home Wi-Fi.")
        val json = hubCall { Http.post(url("/kid/requests/$id/change"), JSONObject().put("minutes", minutes), token, timeoutMs = 8_000) }
        merge(listOf(parseRequest(json.getJSONObject("request"), System.currentTimeMillis())))
        wake.trySend(Unit)
    }

    /** Runs a hub call, turning failures into a message a parent can act on. */
    private fun hubCall(block: () -> JSONObject): JSONObject = try {
        block()
    } catch (e: HttpError) {
        throw IllegalStateException(
            when (e.code) {
                "not_running" -> "That call time has already ended."
                else -> e.message ?: "The hub said no"
            }
        )
    } catch (e: java.io.IOException) {
        throw IllegalStateException("Couldn't reach the hub. This needs the home Wi-Fi, so every phone stays the same.")
    }

    /** Something changed on the phone (an app installed or removed): tell the hub soon. */
    fun poke() {
        wake.trySend(Unit)
    }

    /** Starts the link. Called once when the app starts. */
    fun watch(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        switches.value = Switches(
            on = prefs.getStringSet(KEY_ALLOWED, emptySet()).orEmpty().toSet(),
            ends = runCatching {
                val o = JSONObject(prefs.getString(KEY_APP_ENDS, null) ?: "{}")
                o.keys().asSequence().associateWith { o.getLong(it) }
            }.getOrDefault(emptyMap()),
        )
        _windows.value = runCatching {
            val o = JSONObject(prefs.getString(KEY_WINDOWS, null) ?: "{}")
            o.keys().asSequence().associateWith { o.getLong(it) }.filterValues { it > System.currentTimeMillis() }
        }.getOrDefault(emptyMap())
        _status.value = if (isSetUp) HomeStatus.Checking else HomeStatus.NotSetUp
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    if (isSetUp) _status.value = HomeStatus.Checking
                    wokeUp.value++
                }
                wake.trySend(Unit)
            }
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })

        // The apps he may open now: an app goes the moment its time is up. Timers don't run in
        // deep sleep, so this looks again whenever the screen comes on.
        scope.launch {
            combine(switches, wokeUp) { s, _ -> s }.collectLatest { s ->
                while (true) {
                    val now = System.currentTimeMillis()
                    val open = s.on.filterTo(mutableSetOf()) { (s.ends[it] ?: Long.MAX_VALUE) > now }
                    _appEnds.value = s.ends.filterKeys { it in open }
                    _allowed.value = open
                    val next = _appEnds.value.values.minOrNull() ?: break
                    delay(next - now)
                }
            }
        }
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
                    if (_status.value != HomeStatus.AtHome) BatteryReport.backHome(app)
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
        val now = System.currentTimeMillis()
        val allowed = json.optJSONArray("allowedPackages").strings().toSet()
        // Times are relative, so the phone and Pi clocks don't need to agree. The same end heard
        // again a moment later keeps the time it had, so the countdown on screen doesn't jump.
        val times = json.optJSONArray("appTimes")
        val ends = (0 until (times?.length() ?: 0)).associate { i ->
            val o = times!!.getJSONObject(i)
            val pkg = o.getString("package")
            val heard = now + o.getLong("remainingSeconds") * 1000
            val known = switches.value.ends[pkg]
            pkg to if (known != null && abs(known - heard) < SAME_END_MS) known else heard
        }
        setSwitches(allowed, ends)
        val requests = json.optJSONArray("requests")
        merge((0 until (requests?.length() ?: 0)).map { parseRequest(requests!!.getJSONObject(it), now) }, replaceAll = true)
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
