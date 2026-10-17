package ovh.tenjo.kidphone.kiosk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.telecom.TelecomManager
import android.util.Log
import ovh.tenjo.kidphone.HomeActivity
import ovh.tenjo.kidphone.data.Store

/**
 * Locks and unlocks the phone.
 *
 * Locking uses Android's "lock task" mode, which only a device-owner app can start without the
 * user being able to leave it. While locked:
 *  - only this app can run: no home, no recent apps, no other app can open on top;
 *  - the notification shade and quick settings (Wi-Fi, Bluetooth, airplane mode...) are disabled;
 *    the status bar still shows the clock, signal and battery;
 *  - the power-button menu is off, so the phone can't be switched off from it;
 *  - there is no lock screen, so waking the phone shows the call buttons;
 *  - this app is the home screen, so a restart comes straight back to it;
 *  - safe mode, factory reset from Settings and adding users are blocked.
 */
object Kiosk {
    private const val TAG = "KidPhone"

    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_ADD_USER,
    )

    private val SELF_GRANTED = buildList {
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun admin(context: Context) = ComponentName(context, AdminReceiver::class.java)

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)
    private fun telecom(context: Context) = context.getSystemService(TelecomManager::class.java)
    private fun homeScreen(context: Context) = ComponentName(context, HomeActivity::class.java)

    fun isDeviceOwner(context: Context): Boolean = dpm(context).isDeviceOwnerApp(context.packageName)

    fun isInLockTask(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).lockTaskModeState !=
            ActivityManager.LOCK_TASK_MODE_NONE

    /** Opens the home screen, which is the kid screen while the phone is locked. */
    fun homeIntent(): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Puts the phone in kid mode. Kid mode always runs in the home screen ([HomeActivity]), so the
     * Home button, a restart and the app icon all lead to the same screen. Called from the app
     * icon's screen, this only prepares the phone; that screen then hands over to the home screen.
     */
    fun lock(activity: Activity, isHomeScreen: Boolean): Boolean {
        if (!isDeviceOwner(activity)) return false
        // Every time kid mode starts or the kid screen comes back: full volume, no silent mode.
        Loudness.makeLoud(activity)
        if (isInLockTask(activity)) {
            Store.get(activity).locked = true
            return true
        }
        applyPolicies(activity)
        if (isHomeScreen) step("start lock task") { activity.startLockTask() }
        return true
    }

    /**
     * Everything about kid mode except actually pinning the screen, which needs the screen itself.
     * Safe to repeat. Also used after a restart or an app update, before the kid screen opens.
     */
    fun applyPolicies(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        Store.get(context).locked = true
        val dpm = dpm(context)
        val admin = admin(context)
        step("allow lock task") { dpm.setLockTaskPackages(admin, arrayOf(context.packageName)) }
        step("lock task features") {
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
        }
        step("home screen") {
            context.packageManager.setComponentEnabledSetting(
                homeScreen(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
            dpm.addPersistentPreferredActivity(admin, home, homeScreen(context))
        }
        step("status bar") { dpm.setStatusBarDisabled(admin, true) }
        step("lock screen") {
            if (!dpm.setKeyguardDisabled(admin, true)) {
                Log.w(TAG, "Lock screen not disabled: the phone has a screen-lock PIN or pattern set")
            }
        }
        RESTRICTIONS.forEach { r -> step("restrict $r") { dpm.addUserRestriction(admin, r) } }
        grantOwnPermissions(context)
        takeDialerRoleSilently(context)
        Loudness.makeLoud(context)
        return true
    }

    /** Leaves kid mode and gives the phone back as a normal Android phone. */
    fun unlock(activity: Activity) {
        Store.get(activity).locked = false // first, so nothing restarts kid mode while leaving
        val dpm = dpm(activity)
        val admin = admin(activity)
        step("stop lock task") { if (isInLockTask(activity)) activity.stopLockTask() }
        step("home screen") {
            dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName)
            activity.packageManager.setComponentEnabledSetting(
                homeScreen(activity),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        step("status bar") { dpm.setStatusBarDisabled(admin, false) }
        step("lock screen") { dpm.setKeyguardDisabled(admin, false) }
        RESTRICTIONS.forEach { r -> step("unrestrict $r") { dpm.clearUserRestriction(admin, r) } }
        giveBackDialerRole(activity)
    }

    /** Unlocks, then stops being device owner so the app can be uninstalled like any other. */
    fun removeCompletely(activity: Activity) {
        unlock(activity)
        step("set lock task packages") { dpm(activity).setLockTaskPackages(admin(activity), emptyArray()) }
        step("clear device owner") { dpm(activity).clearDeviceOwnerApp(activity.packageName) }
        Store.get(activity).setupDone = false
    }

    /** As device owner the app can grant its own phone permissions, so no pop-ups are needed. */
    fun grantOwnPermissions(context: Context) {
        if (!isDeviceOwner(context)) return
        SELF_GRANTED.forEach { p ->
            step("grant $p") {
                dpm(context).setPermissionGrantState(
                    admin(context), context.packageName, p,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }
    }

    // ---- Being the phone's calling app ----
    //
    // The kid needs our own call screen: the phone's normal one has buttons (add call, keypad,
    // contacts) that would let him ring anyone. Being the calling app also lets us turn away
    // callers who are not on the list.

    fun isDefaultDialer(context: Context): Boolean =
        telecom(context).defaultDialerPackage == context.packageName

    /** Android 14+ lets a device owner pick the calling app without asking. Older phones ask once. */
    fun takeDialerRoleSilently(context: Context): Boolean {
        if (isDefaultDialer(context)) return true
        rememberPreviousDialer(context)
        if (Build.VERSION.SDK_INT >= 34 && isDeviceOwner(context)) {
            step("take dialer role") { dpm(context).setDefaultDialerApplication(context.packageName) }
        }
        return isDefaultDialer(context)
    }

    fun dialerRoleRequest(context: Context): Intent {
        rememberPreviousDialer(context)
        return context.getSystemService(RoleManager::class.java)
            .createRequestRoleIntent(RoleManager.ROLE_DIALER)
    }

    private fun rememberPreviousDialer(context: Context) {
        val current = telecom(context).defaultDialerPackage
        if (current != null && current != context.packageName) Store.get(context).previousDialer = current
    }

    /**
     * On unlock, hands calls back to the phone's own phone app so it behaves normally. Only
     * possible on Android 14+; on older phones this app keeps handling calls while unlocked.
     */
    private fun giveBackDialerRole(context: Context) {
        if (Build.VERSION.SDK_INT < 34 || !isDefaultDialer(context)) return
        val previous = Store.get(context).previousDialer ?: telecom(context).systemDialerPackage
        if (previous == null || previous == context.packageName) return
        step("give back dialer role") { dpm(context).setDefaultDialerApplication(previous) }
    }

    /** Runs one step, logging failures, so one refused policy never stops the others. */
    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Kiosk step '$name' failed", e)
        }
    }
}
