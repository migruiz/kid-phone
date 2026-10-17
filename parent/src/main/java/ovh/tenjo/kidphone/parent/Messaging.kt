package ovh.tenjo.kidphone.parent

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Messages from the home hub, delivered by Firebase even when the app is closed. */
class Messaging : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { runCatching { Hub.updateFcm(token) }.onFailure { Log.w(TAG, "Couldn't send the new FCM token", it) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        Log.i(TAG, "Message from the hub: ${d["type"]}")
        val id = d["id"] ?: return
        when (d["type"]) {
            "call_request" -> Notifications.showRequest(
                this, id, d["childName"].orEmpty(), d["contactName"].orEmpty(),
                d["expiresAt"]?.toLongOrNull() ?: (System.currentTimeMillis() + 5 * 60_000),
            )
            // Answered (maybe by the other parent), expired or cancelled: say how it ended.
            "request_closed" -> Notifications.showClosed(
                this, id, Notifications.outcome(d["status"].orEmpty(), d["minutes"]?.toIntOrNull(), d["by"]),
            )
        }
    }

    companion object {
        private const val TAG = "KidPhoneParent"
    }
}
