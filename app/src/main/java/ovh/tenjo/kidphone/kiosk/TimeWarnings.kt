package ovh.tenjo.kidphone.kiosk

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ovh.tenjo.kidphone.call.CallManager
import ovh.tenjo.kidphone.data.Store
import ovh.tenjo.kidphone.hub.Apps
import ovh.tenjo.kidphone.hub.Hub

/**
 * Tells the child how long is left of an app the parents switched on for a while: "10 minutes
 * left" and "5 minutes left" for a few seconds each, then for the last 30 seconds a countdown
 * (30, 29, 28…) and "Time's up!". Then the app closes (see [Hub.openNow]).
 *
 * They float at the top of whatever is on screen, the app itself included, without getting in its
 * way: taps go through to the app underneath. That needs "display over other apps", which being
 * the phone app gives Kid Phone on Android 14; elsewhere a parent allows it once (see [canShow] and
 * the parent menu). Until then the warnings are short pop-up messages instead.
 *
 * Only in kid mode, with the screen on, and never over a call. A reminder missed by more than a
 * minute (the screen was off) is skipped, and so is one that would come as the time starts (10
 * minutes given: no "10 minutes left").
 */
object TimeWarnings {
    private const val TAG = "KidPhone"
    private val REMINDER_MINUTES = listOf(10, 5)
    private const val COUNTDOWN_SECONDS = 30
    private const val MINUTE = 60_000L
    private const val REMINDER_SHOWS_MS = 7_000L
    private const val TIMES_UP_SHOWS_MS = 3_000L
    /** A reminder due this soon after a time was given would only repeat it: skipped. */
    private const val JUST_STARTED_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: Context

    /** When each end time ("package@end") was first heard of, to skip reminders as a time starts. */
    private val firstHeard = HashMap<String, Long>()
    /** Reminders already given or skipped, as "package@end@minutes". */
    private val given = HashSet<String>()

    /** The card on screen, if any, and when a reminder or "Time's up!" card goes. */
    private var shown: LinearLayout? = null
    private var hideAt = 0L
    /** The app whose countdown is on screen, and when its time is up. */
    private var countdownOf: String? = null
    private var countdownEnd = 0L

    /** Whether the warnings can show over other apps. If not, a parent can allow it once. */
    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Android's "display over other apps" switch for Kid Phone, for a parent to turn on. */
    fun allowRequest(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    /** Starts watching the apps' times. Called once when the app starts, after Hub.watch. */
    fun start(context: Context) {
        app = context.applicationContext
        scope.launch {
            // Timers don't run in deep sleep: look again as soon as the screen comes on.
            combine(Hub.appEnds, Hub.screenOns) { ends, _ -> ends }.collectLatest { ends ->
                while (true) {
                    val wait = runCatching { check(ends) }
                        .onFailure { Log.w(TAG, "Time warnings failed", it) }
                        .getOrDefault(1_000L)
                    delay(wait.coerceAtLeast(50L))
                }
            }
        }
    }

    /** Shows what is due now. Returns how long to wait before looking again. */
    private fun check(ends: Map<String, Long>): Long {
        val now = System.currentTimeMillis()
        val heardKeys = ends.map { (pkg, end) -> "$pkg@$end" }.toSet()
        heardKeys.forEach { firstHeard.getOrPut(it) { now } }
        firstHeard.keys.retainAll(heardKeys)
        given.retainAll { key -> key.substringBeforeLast('@') in heardKeys }

        val canWarn = Store.get(app).locked && !CallManager.hasLiveCall &&
            app.getSystemService(PowerManager::class.java).isInteractive
        if (!canWarn) {
            hide()
            return 1_000L
        }

        // A countdown that is no longer running: its time is up, or a parent changed it.
        countdownOf?.let { pkg ->
            if (ends[pkg] != countdownEnd) {
                if (pkg !in ends && now >= countdownEnd - 1_000) timesUp(pkg) else hide()
            }
        }
        if (shown != null && countdownOf == null && now >= hideAt) hide()

        // The last seconds of the app that runs out first.
        val soonest = ends.entries.minByOrNull { it.value }
        if (soonest != null && soonest.value - now <= COUNTDOWN_SECONDS * 1000L) {
            val left = soonest.value - now
            countdown(soonest.key, soonest.value, ((left + 999) / 1000).toInt())
            return (left % 1000).takeIf { it > 0 } ?: 1_000L
        }

        for ((pkg, end) in ends) {
            for (minutes in REMINDER_MINUTES) {
                val at = end - minutes * MINUTE
                val key = "$pkg@$end@$minutes"
                if (now < at || key in given) continue
                given += key
                val heard = firstHeard["$pkg@$end"] ?: now
                // Not more than a minute late, and not as the time starts.
                if (now - at < MINUTE && at - heard >= JUST_STARTED_MS) remind(pkg, minutes)
            }
        }

        // Each second near the end of a time, otherwise when the next reminder is near.
        val left = soonest?.let { it.value - now }
        var wait = when {
            left == null -> 60_000L
            left <= 11 * MINUTE -> 1_000L
            else -> minOf(60_000L, left - 11 * MINUTE)
        }
        if (shown != null && countdownOf == null) wait = minOf(wait, hideAt - now)
        return wait
    }

    // ---- What the child sees ----

    private fun remind(pkg: String, minutes: Int) {
        val label = Apps.label(app, pkg)
        Log.i(TAG, "$label: $minutes minutes left")
        chime()
        if (!canShow(app)) {
            Toast.makeText(app, "⏰ $label: $minutes minutes left", Toast.LENGTH_LONG).show()
            return
        }
        val card = card(pkg, 44)
        card.addView(column(text("⏰ $minutes minutes left", 22f, 900), text(label, 16f, 700, alpha = 0.8f)))
        show(card)
        hideAt = System.currentTimeMillis() + REMINDER_SHOWS_MS
    }

    private fun countdown(pkg: String, end: Long, seconds: Int) {
        val label = Apps.label(app, pkg)
        if (countdownOf != pkg || countdownEnd != end) {
            Log.i(TAG, "$label: last $seconds seconds")
            if (countdownOf != pkg) chime()
            hide()
            countdownOf = pkg
            countdownEnd = end
            if (canShow(app)) {
                val card = card(pkg, 40)
                card.addView(column(text("$label ends in", 16f, 800, alpha = 0.85f).apply { id = CAPTION }, text("", 40f, 900).apply { id = NUMBER }))
                show(card)
            }
        }
        val card = shown
        if (card == null) {
            // No floating card allowed: a pop-up message at the start and at ten seconds.
            if (seconds == COUNTDOWN_SECONDS || seconds == 10) {
                Toast.makeText(app, "⏰ $label ends in $seconds seconds", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val number = card.findViewById<TextView>(NUMBER)
        number.text = "$seconds"
        // The last ten seconds turn red, and each new number pops a little.
        (card.background as? GradientDrawable)?.setColor(if (seconds <= 10) RED else NIGHT)
        number.pivotX = 0f // grows to the right, into the card, never past its left edge
        number.scaleX = 1.35f
        number.scaleY = 1.35f
        number.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
    }

    private fun timesUp(pkg: String) {
        Log.i(TAG, "${Apps.label(app, pkg)}: time's up")
        countdownOf = null
        val card = shown ?: return
        card.findViewById<TextView>(CAPTION)?.text = Apps.label(app, pkg)
        card.findViewById<TextView>(NUMBER)?.text = "Time's up!"
        (card.background as? GradientDrawable)?.setColor(RED)
        hideAt = System.currentTimeMillis() + TIMES_UP_SHOWS_MS
    }

    /** Puts [view] on screen in place of any card showing. */
    private fun show(view: LinearLayout) {
        removeCard()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never takes a tap or a key: the app underneath carries on as if it wasn't there.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(56)
            // Android 12+ only lets taps through to another app's window under it if this is at most
            // 80% opaque. Older Android has no such rule: fully solid, easier to read.
            alpha = if (Build.VERSION.SDK_INT >= 31) 0.8f else 1f
            title = "Kid Phone time left"
        }
        runCatching { app.getSystemService(WindowManager::class.java).addView(view, params) }
            .onSuccess { shown = view }
            .onFailure { Log.w(TAG, "Could not show the time left", it) }
    }

    /** Takes any card off the screen, and stops the countdown. */
    private fun hide() {
        countdownOf = null
        removeCard()
    }

    private fun removeCard() {
        val view = shown ?: return
        shown = null
        runCatching { app.getSystemService(WindowManager::class.java).removeView(view) }
    }

    /** The phone's notification sound, once, so a child busy with a game notices. */
    private fun chime() {
        runCatching {
            RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        }
    }

    // ---- Plain views: these float over other apps, outside any of our screens ----

    private val NUMBER = View.generateViewId()
    private val CAPTION = View.generateViewId()
    private const val NIGHT = 0xF2161C36.toInt()
    private const val RED = 0xF2D92D20.toInt()

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), app.resources.displayMetrics).toInt()

    private val nunito: Typeface? by lazy {
        runCatching { ResourcesCompat.getFont(app, ovh.tenjo.kidphone.shared.R.font.nunito) }.getOrNull()
    }

    /** A rounded night-blue card with the app's icon at the start. */
    private fun card(pkg: String, iconSize: Int) = LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        setPadding(dp(18), dp(14), dp(28), dp(14))
        minimumWidth = dp(240)
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(NIGHT)
            setStroke(dp(2), 0x80FFC83D.toInt())
        }
        val icon = runCatching { app.packageManager.getApplicationIcon(pkg) }.getOrNull()
        if (icon != null) addView(ImageView(app).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))
    }

    private fun column(vararg views: View) = LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        setPadding(dp(14), 0, 0, 0)
        views.forEach { addView(it) }
    }

    private fun text(value: String, sp: Float, weight: Int, alpha: Float = 1f) = TextView(app).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(android.graphics.Color.WHITE)
        this.alpha = alpha
        typeface = nunito
        fontVariationSettings = "'wght' $weight"
        includeFontPadding = false
    }
}
