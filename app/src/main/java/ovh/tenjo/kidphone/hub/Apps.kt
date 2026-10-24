package ovh.tenjo.kidphone.hub

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.telecom.TelecomManager
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream

/** An app the child could open from the Apps screen, if the parents allow it. */
data class LaunchableApp(val packageName: String, val label: String, val updatedAt: Long)

/** The apps on this phone, as the parents see them in Kid Phone Parent. */
object Apps {
    /**
     * Never offered to the parents: each would undo kid mode (Settings, Play Store, installers) or
     * dial any number (the phone's own Phone app, the SIM menu).
     */
    private val NEVER = setOf(
        "com.android.settings",
        "com.android.vending",
        "com.android.stk",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )

    /** Every app with a launcher icon, except Kid Phone and the [NEVER] ones, by name. */
    fun launchable(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val never = NEVER + listOfNotNull(
            context.packageName,
            runCatching { context.getSystemService(TelecomManager::class.java).systemDialerPackage }.getOrNull(),
        )
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it !in never }
            .mapNotNull { pkg ->
                runCatching {
                    val info = pm.getPackageInfo(pkg, 0)
                    val label = info.applicationInfo?.loadLabel(pm)?.toString()?.trim().orEmpty()
                    LaunchableApp(pkg, label.ifBlank { pkg }, info.lastUpdateTime)
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }

    /** The app's icon as a 96×96 PNG in base64, for the parent app's list. */
    fun iconPng(context: Context, packageName: String): String? = runCatching {
        val bitmap = context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96, Bitmap.Config.ARGB_8888)
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }.getOrNull()

    /** The app's name as the phone shows it, or the package name if it isn't on the phone. */
    fun label(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString().trim()
    }.getOrNull()?.ifBlank { null } ?: packageName

    fun launchIntent(context: Context, packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
