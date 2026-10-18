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
 *
 * Also the battery alerts, each in its own channel so a parent can tune each one in Android's
 * settings: low (below 25%) makes a sound, very low (15%) also pops up, critical (10%) pops up
 * and vibrates hard.
 */
object Notifications {
    private const val CHANNEL_REQUESTS = "call_requests"
    private const val CHANNEL_ANSWERS = "call_answers"
    private const val CHANNEL_BATTERY_LOW = "battery_low"
    private const val CHANNEL_BATTERY_VERY_LOW = "battery_very_low"
    private const val CHANNEL_BATTERY_CRITICAL = "battery_critical"
    private const val CHANNEL_BATTERY_PLUG = "battery_plug"
    private const val NAMES = "request_names"
    /** One battery notification at a time: each one replaces the last. */
    private const val BATTERY_ID = 0x0BA77E21

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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BATTERY_LOW, "Battery low (below 25%)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Your child's phone battery is below 25%"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BATTERY_VERY_LOW, "Battery very low (below 15%)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Your child's phone battery is below 15%"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BATTERY_CRITICAL, "Battery critical (below 10%)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Your child's phone battery is below 10% and needs charging now"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 250, 600, 250, 600)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BATTERY_PLUG, "Battery charging or unplugged", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Updates a battery alert when the phone is plugged in or out"
            }
        )
    }

    /**
     * A battery alert ([level] low, very_low or critical), or, after one, the phone being plugged
     * in or out (charging, unplugged), which quietly updates the alert if it is still showing.
     */
    fun showBattery(context: Context, level: String, percent: Int, childName: String) {
        val nm = manager(context)
        val name = childName.ifBlank { "Your child" }
        val plug = level == "charging" || level == "unplugged"
        if (plug && nm.activeNotifications.none { it.id == BATTERY_ID }) return
        channels(context)
        val (channel, title, text) = when (level) {
            "critical" -> Triple(CHANNEL_BATTERY_CRITICAL, "$name's battery is critical: $percent%", "Charge it now, or the phone will switch off.")
            "very_low" -> Triple(CHANNEL_BATTERY_VERY_LOW, "$name's battery is very low: $percent%", "The phone needs charging soon.")
            "low" -> Triple(CHANNEL_BATTERY_LOW, "$name's battery is low: $percent%", "Time to charge the phone.")
            "charging" -> Triple(CHANNEL_BATTERY_PLUG, "$name's phone is charging", "It was plugged in at $percent%.")
            "unplugged" -> Triple(CHANNEL_BATTERY_PLUG, "$name's phone was unplugged", "The battery is at $percent%. It still needs charging.")
            else -> return
        }
        nm.notify(
            BATTERY_ID,
            Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .apply { if (level == "critical") setColor(0xFFE11D48.toInt()) }
                .build(),
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
