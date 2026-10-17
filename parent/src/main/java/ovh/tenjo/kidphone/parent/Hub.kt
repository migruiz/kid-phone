package ovh.tenjo.kidphone.parent

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ovh.tenjo.kidphone.shared.Http
import ovh.tenjo.kidphone.shared.HttpError
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Child(val name: String, val paired: Boolean, val lastSeenAgoMs: Long?, val appVersion: Long?)

data class KidApp(val packageName: String, val label: String, val allowed: Boolean, val installed: Boolean, val hasIcon: Boolean)

data class CallRequest(
    val id: String,
    val contactName: String,
    val number: String,
    /** pending, approved, rejected, expired or cancelled */
    val status: String,
    val decidedBy: String?,
    val minutes: Int?,
    val remainingSeconds: Long?,
    val agoMs: Long,
)

data class Parent(val name: String, val me: Boolean)

data class HubState(
    val child: Child,
    val apps: List<KidApp>,
    val requests: List<CallRequest>,
    val parents: List<Parent>,
)

/**
 * The home hub on the Raspberry Pi, reached from anywhere through Cloudflare. See hub/API.md.
 * This phone is "paired" once it holds a token from the hub.
 */
object Hub {
    /** https://kidphone.tenjo.ovh (a debug build can point elsewhere, see build.gradle.kts). */
    const val BASE = BuildConfig.HUB_URL

    private const val PREFS = "pairing"
    private const val KEY_TOKEN = "token"
    private const val KEY_NAME = "name"
    private const val KEY_CHILD = "childName"

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _paired = MutableStateFlow(false)
    val paired: StateFlow<Boolean> = _paired

    fun init(context: Context) {
        app = context.applicationContext
        _paired.value = token != null
    }

    private val token: String? get() = prefs.getString(KEY_TOKEN, null)
    val myName: String get() = prefs.getString(KEY_NAME, null).orEmpty()
    /** The child's name as last heard from the hub, for screens shown before the state loads. */
    val childName: String get() = prefs.getString(KEY_CHILD, null).orEmpty()

    /** Pairs this phone using the code shown on the child's phone. Throws with a readable message. */
    suspend fun pair(code: String, name: String) = withContext(Dispatchers.IO) {
        val fcm = runCatching { fcmToken() }.getOrNull()
        val body = JSONObject().put("code", code).put("name", name).put("fcmToken", fcm ?: "")
        val answer = try {
            Http.post("$BASE/parent/pair", body)
        } catch (e: HttpError) {
            throw IllegalStateException(
                when (e.code) {
                    "bad_code" -> "That code didn't work. Codes work once, for 10 minutes. Get a new one on the child's phone."
                    "too_many_tries" -> "Too many wrong codes. Wait 10 minutes and try again."
                    else -> e.message
                }
            )
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Can't reach the home hub. Check this phone's internet connection.")
        }
        prefs.edit()
            .putString(KEY_TOKEN, answer.getString("token"))
            .putString(KEY_NAME, name)
            .putString(KEY_CHILD, answer.optString("childName"))
            .apply()
        _paired.value = true
    }

    suspend fun state(): HubState = call { Http.get("$BASE/parent/state", token) }.let(::parseState).also {
        if (it.child.name.isNotBlank()) prefs.edit().putString(KEY_CHILD, it.child.name).apply()
    }

    fun iconUrl(packageName: String) = "$BASE/parent/apps/$packageName/icon"
    fun authHeader(): String = "Bearer ${token.orEmpty()}"

    suspend fun setAllowed(packageName: String, allowed: Boolean) {
        call { Http.put("$BASE/parent/apps/$packageName", JSONObject().put("allowed", allowed), token) }
    }

    /** Answers an "ask first" request: 0 minutes rejects it. Returns false if the other parent was first. */
    suspend fun answer(requestId: String, minutes: Int): Boolean =
        call { Http.post("$BASE/parent/requests/$requestId/answer", JSONObject().put("minutes", minutes), token) }
            .let { !it.optBoolean("alreadyAnswered") }

    suspend fun updateFcm(fcmToken: String) {
        if (token == null) return
        call { Http.put("$BASE/parent/fcm", JSONObject().put("fcmToken", fcmToken), token) }
    }

    suspend fun replaceKid() {
        call { Http.post("$BASE/parent/replace-kid", token = token) }
    }

    /** Forgets the pairing here, and tells the hub (best effort). */
    suspend fun unpair() {
        runCatching { call { Http.delete("$BASE/parent/me", token) } }
        forgetLocally()
    }

    private fun forgetLocally() {
        prefs.edit().remove(KEY_TOKEN).apply()
        _paired.value = false
    }

    /** Runs a hub call off the main thread. A 401 means the hub no longer knows this phone. */
    private suspend fun <T> call(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: HttpError) {
            if (e.status == 401) forgetLocally()
            throw e
        }
    }

    suspend fun fcmToken(): String = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) cont.resume(task.result) else cont.resumeWithException(task.exception ?: Exception("No FCM token"))
        }
    }

    private fun parseState(json: JSONObject): HubState {
        val now = json.optLong("time", System.currentTimeMillis())
        val c = json.getJSONObject("child")
        val child = Child(
            name = c.optString("name"),
            paired = c.optBoolean("paired"),
            lastSeenAgoMs = if (c.isNull("lastSeen")) null else (now - c.getLong("lastSeen")).coerceAtLeast(0),
            appVersion = if (c.isNull("appVersion")) null else c.optLong("appVersion"),
        )
        val apps = json.optJSONArray("apps").objects().map {
            KidApp(it.getString("package"), it.optString("label"), it.optBoolean("allowed"), it.optBoolean("installed", true), it.optBoolean("hasIcon"))
        }
        val requests = json.optJSONArray("requests").objects().map {
            CallRequest(
                id = it.getString("id"),
                contactName = it.optString("contactName"),
                number = it.optString("number"),
                status = it.optString("status"),
                decidedBy = it.optString("decidedBy").ifBlank { null },
                minutes = if (it.has("minutes") && !it.isNull("minutes")) it.getInt("minutes") else null,
                remainingSeconds = if (it.has("remainingSeconds") && !it.isNull("remainingSeconds")) it.getLong("remainingSeconds") else null,
                agoMs = (now - it.optLong("createdAt", now)).coerceAtLeast(0),
            )
        }
        val parents = json.optJSONArray("parents").objects().map { Parent(it.optString("name"), it.optBoolean("me")) }
        return HubState(child, apps, requests, parents)
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
