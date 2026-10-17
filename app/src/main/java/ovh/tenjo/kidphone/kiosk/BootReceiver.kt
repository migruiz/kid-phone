package ovh.tenjo.kidphone.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ovh.tenjo.kidphone.data.Store

/**
 * After a restart (or an app update, which also ends kid mode) reopens the kid screen if the phone
 * was locked. The kid-mode settings are applied again first, so an update that changes them
 * (such as which screen is the home screen) takes effect before anything else can open.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Store.get(context).locked || !Kiosk.isDeviceOwner(context)) return
        Log.i("KidPhone", "Phone was locked before ${intent.action}, reopening kid mode")
        Kiosk.applyPolicies(context)
        runCatching { context.startActivity(Kiosk.homeIntent()) }
    }
}
