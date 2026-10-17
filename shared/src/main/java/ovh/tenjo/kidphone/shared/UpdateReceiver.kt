package ovh.tenjo.kidphone.shared

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Android reports here how an update install went. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val version = intent.getStringExtra(EXTRA_VERSION) ?: "?"
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                Updater.onNeedsTap(context, confirm, version)
            }
            // On success the app is replaced and restarted, so there is usually nobody left to tell.
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Updater.onInstallFailed(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android refused the update"
            )
        }
    }

    companion object {
        const val EXTRA_VERSION = "version"
    }
}
