package ovh.tenjo.kidphone.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ovh.tenjo.kidphone.data.Store

/** The hook Android uses to make this app the phone's device owner (set once with adb). */
class AdminReceiver : DeviceAdminReceiver() {

    /**
     * Kid mode ended without a parent unlocking it, e.g. the app crashed. Bring it straight back.
     * A parent unlock clears the flag before ending kid mode, so this does not fire then.
     */
    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        if (Store.get(context).locked) {
            Log.w(TAG, "Kid mode ended unexpectedly, restarting it")
            Kiosk.applyPolicies(context)
            runCatching { context.startActivity(Kiosk.homeIntent()) }
        }
    }

    companion object {
        private const val TAG = "KidPhone"
    }
}
