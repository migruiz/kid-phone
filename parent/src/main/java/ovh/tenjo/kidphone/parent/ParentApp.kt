package ovh.tenjo.kidphone.parent

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ovh.tenjo.kidphone.shared.Updater

class ParentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Updater.configure(this, assetName = "kid-phone-parent.apk")
        Updater.schedule(this, hours = 6, wifiOnly = false)
        Hub.init(this)
        // Runs whenever the app starts, including in the background for the update check.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { Hub.syncFcmToken() } }
    }
}
