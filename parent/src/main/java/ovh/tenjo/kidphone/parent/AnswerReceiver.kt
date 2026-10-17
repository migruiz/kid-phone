package ovh.tenjo.kidphone.parent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A parent tapped No / 10 min / 30 min on the alert: send the answer to the hub. */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val minutes = intent.getIntExtra(EXTRA_MINUTES, -1).takeIf { it >= 0 } ?: return
        val app = context.applicationContext
        Notifications.showClosed(app, id, "Sending…", onlyIfShowing = false)
        val done = goAsync()
        scope.launch {
            val text = try {
                val result = Hub.answer(id, minutes)
                val r = result.request
                val outcome = Notifications.outcome(r.status, r.minutes, r.decidedBy)
                if (result.alreadyAnswered) "Already answered. $outcome" else outcome
            } catch (e: Exception) {
                "Couldn't send the answer. Open Kid Phone Parent to answer."
            }
            Notifications.showClosed(app, id, text, onlyIfShowing = false)
            done.finish()
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_MINUTES = "minutes"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
