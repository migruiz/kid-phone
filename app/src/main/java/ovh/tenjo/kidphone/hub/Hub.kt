package ovh.tenjo.kidphone.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import ovh.tenjo.kidphone.shared.Http

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
 */
object Hub {
    private const val TAG = "KidPhoneHub"
    const val DEFAULT_ADDRESS = "192.168.0.11:8774"

    private const val PREFS = "hub"
    private const val KEY_ADDRESS = "address"
    private const val KEY_TOKEN = "token"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _status = MutableStateFlow(HomeStatus.NotSetUp)
    val status: StateFlow<HomeStatus> = _status

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
        } catch (e: ovh.tenjo.kidphone.shared.HttpError) {
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
        prefs.edit().putString(KEY_ADDRESS, clean).putString(KEY_TOKEN, answer.getString("token")).apply()
        _status.value = HomeStatus.Checking
        wake.trySend(Unit)
    }

    /** Stops using the hub. */
    fun forget() {
        prefs.edit().remove(KEY_TOKEN).apply()
        _status.value = HomeStatus.NotSetUp
        wake.trySend(Unit)
    }

    /** A one-time code a parent types into Kid Phone Parent to pair their phone. */
    suspend fun pairingCode(): String = withContext(Dispatchers.IO) {
        Http.post(url("/kid/pairing-code"), token = token, timeoutMs = 8_000).getString("code")
    }

    /**
     * Keeps [status] current while the screen is on: asks the hub every 30 seconds, and straight
     * away when the screen comes on or the network changes. Does nothing while the screen is off.
     */
    fun watch(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
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
        val power = app.getSystemService(PowerManager::class.java)
        scope.launch {
            var failures = 0
            while (isActive) {
                if (!isSetUp || !power.isInteractive) {
                    withTimeoutOrNull(5 * 60_000L) { wake.receive() }
                    continue
                }
                val ok = ping()
                if (!isSetUp) continue // the hub forgot this phone
                if (ok) {
                    failures = 0
                    _status.value = HomeStatus.AtHome
                } else {
                    failures++
                    // One missed answer right after waking is normal (Wi-Fi reconnecting).
                    if (failures >= 2) _status.value = HomeStatus.Away
                }
                withTimeoutOrNull(if (ok) 30_000L else 8_000L) { wake.receive() }
            }
        }
    }

    private fun ping(): Boolean = try {
        Http.get(url("/kid/ping"), token, timeoutMs = 4_000)
        true
    } catch (e: ovh.tenjo.kidphone.shared.HttpError) {
        if (e.status == 401) {
            Log.w(TAG, "The hub no longer knows this phone")
            prefs.edit().remove(KEY_TOKEN).apply()
            _status.value = HomeStatus.NotSetUp
        }
        false
    } catch (e: Exception) {
        false
    }
}
