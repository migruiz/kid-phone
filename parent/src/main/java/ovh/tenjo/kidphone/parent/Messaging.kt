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
        Log.i(TAG, "Message from the hub: ${message.data["type"]}")
    }

    companion object {
        private const val TAG = "KidPhoneParent"
    }
}
