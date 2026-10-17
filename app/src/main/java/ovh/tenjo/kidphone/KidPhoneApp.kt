package ovh.tenjo.kidphone

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.telecom.TelecomManager
import ovh.tenjo.kidphone.call.CallManager
import ovh.tenjo.kidphone.hub.Hub
import ovh.tenjo.kidphone.shared.Updater

class KidPhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Updates come from GitHub over the home Wi-Fi, and only go in while nobody is using the
        // phone: screen off and no call. Installing restarts the app, which ends kid mode for a
        // moment; it comes straight back (see BootReceiver).
        Updater.configure(this, assetName = "kid-phone.apk", canInstallNow = ::isIdle)
        Updater.schedule(this, hours = 1, wifiOnly = true)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = Updater.installDownloadedIfAllowed(context)
        }, IntentFilter(Intent.ACTION_SCREEN_OFF))
        Hub.watch(this)
    }

    private fun isIdle(context: Context): Boolean {
        if (context.getSystemService(PowerManager::class.java).isInteractive) return false
        if (CallManager.hasCalls) return false
        val inCall = runCatching { context.getSystemService(TelecomManager::class.java).isInCall }.getOrDefault(false)
        return !inCall
    }
}
