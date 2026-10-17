package ovh.tenjo.kidphone.parent

import android.app.Application
import ovh.tenjo.kidphone.shared.Updater

class ParentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Updater.configure(this, assetName = "kid-phone-parent.apk")
        Updater.schedule(this, hours = 6, wifiOnly = false)
        Hub.init(this)
    }
}
