package ovh.tenjo.kidphone.shared

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The server answered with an error. [code] is the hub's short error name, such as "bad_code". */
class HttpError(val status: Int, val code: String?, message: String) : IOException(message)

/**
 * Small JSON-over-HTTP helper for the home hub. Blocking: call it from a background thread
 * (Dispatchers.IO). No library needed for the few calls the apps make.
 */
object Http {
    /** Sent on every hub request so the parents can see which version each phone runs. */
    var appVersion: Long = 0

    fun get(url: String, token: String? = null, timeoutMs: Int = 10_000): JSONObject =
        request("GET", url, token, null, timeoutMs)

    fun post(url: String, body: JSONObject? = null, token: String? = null, timeoutMs: Int = 10_000): JSONObject =
        request("POST", url, token, body ?: JSONObject(), timeoutMs)

    fun put(url: String, body: JSONObject, token: String? = null, timeoutMs: Int = 20_000): JSONObject =
        request("PUT", url, token, body, timeoutMs)

    fun delete(url: String, token: String? = null): JSONObject = request("DELETE", url, token, null, 10_000)

    fun request(method: String, url: String, token: String?, body: JSONObject?, timeoutMs: Int): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = minOf(timeoutMs, 5_000)
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            // Cloudflare (in front of the hub for the parents) turns away some default user agents.
            conn.setRequestProperty("User-Agent", "KidPhone/$appVersion (Android)")
            conn.setRequestProperty("X-App-Version", appVersion.toString())
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes = body.toString().toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (status !in 200..299) {
                throw HttpError(
                    status,
                    json?.optString("error")?.ifBlank { null },
                    json?.optString("message")?.ifBlank { null } ?: "HTTP $status",
                )
            }
            return json ?: JSONObject()
        } finally {
            conn.disconnect()
        }
    }
}
