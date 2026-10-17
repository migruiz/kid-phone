package ovh.tenjo.kidphone.parent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The "Alejandro wants to ring Pablo" alerts. Android shows at most three buttons on a
 * notification, so it has No, 10 min and 30 min; tapping it opens the app, which also offers 1 hour.
 */
object Notifications {
    private const val CHANNEL_REQUESTS = "call_requests"
    private const val CHANNEL_ANSWERS = "call_answers"
    private const val NAMES = "request_names"

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun channels(context: Context) {
        val nm = manager(context)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REQUESTS, "Call requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When your child asks to ring an \"ask first\" person"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ANSWERS, "Answers to call requests", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun idOf(requestId: String) = requestId.hashCode()

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** "Alejandro wants to ring Pablo", with No / 10 min / 30 min. Goes away when the request expires. */
    fun showRequest(context: Context, requestId: String, childName: String, contactName: String, expiresAt: Long) {
        channels(context)
        context.getSharedPreferences(NAMES, Context.MODE_PRIVATE).edit()
            .putString(requestId, "$childName|$contactName").apply()
        val answer = { label: String, minutes: Int ->
            Notification.Action.Builder(
                null, label,
                PendingIntent.getBroadcast(
                    context, idOf(requestId) * 31 + minutes,
                    Intent(context, AnswerReceiver::class.java)
                        .putExtra(AnswerReceiver.EXTRA_ID, requestId)
                        .putExtra(AnswerReceiver.EXTRA_MINUTES, minutes),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()
        }
        val notification = Notification.Builder(context, CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${childName.ifBlank { "Your child" }} wants to ring ${contactName.ifBlank { "someone" }}")
            .setContentText("Answer here, or tap to allow 1 hour.")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setTimeoutAfter((expiresAt - System.currentTimeMillis()).coerceAtLeast(10_000))
            .addAction(answer("No", 0))
            .addAction(answer("10 min", 10))
            .addAction(answer("30 min", 30))
            .build()
        manager(context).notify(idOf(requestId), notification)
    }

    /** Replaces the alert with how it ended (quietly), if it is still showing. */
    fun showClosed(context: Context, requestId: String, text: String, onlyIfShowing: Boolean = true) {
        val nm = manager(context)
        if (onlyIfShowing && nm.activeNotifications.none { it.id == idOf(requestId) }) return
        channels(context)
        val names = context.getSharedPreferences(NAMES, Context.MODE_PRIVATE).getString(requestId, null)?.split("|")
        val title = names?.let { "${it[0]} → ${it.getOrElse(1) { "" }}" } ?: "Call request"
        nm.notify(
            idOf(requestId),
            Notification.Builder(context, CHANNEL_ANSWERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(60_000)
                .build(),
        )
    }

    fun cancel(context: Context, requestId: String) = manager(context).cancel(idOf(requestId))

    /** How a request ended, in a few words: "Dad said yes: 30 min". */
    fun outcome(status: String, minutes: Int?, by: String?): String {
        val who = by?.ifBlank { null } ?: "A parent"
        return when (status) {
            "approved" -> "$who said yes: ${minutes ?: 0} min"
            "rejected" -> "$who said no"
            "expired" -> "Nobody answered in time"
            "cancelled" -> "Stopped asking"
            "ended" -> "$who stopped the call time early"
            else -> "Answered"
        }
    }
}
