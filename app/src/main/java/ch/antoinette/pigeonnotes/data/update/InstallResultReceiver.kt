package ch.antoinette.pigeonnotes.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import ch.antoinette.pigeonnotes.AppGraph

/** Receives the outcome of an installer session that [Updater] committed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = AppGraph.get(context).updater
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system's confirmation screen. Shown by the UI rather than started from here,
                // so it waits for the app to be in front instead of being blocked in the background.
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.let(updater::onConfirmationNeeded)
            }
            PackageInstaller.STATUS_SUCCESS -> updater.onInstalled()
            else -> updater.onInstallFailed(describe(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }

    private fun describe(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "The installation was cancelled."
        PackageInstaller.STATUS_FAILURE_CONFLICT,
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "Android refused the update: it is signed with another key or has a lower version than the app installed." +
                (message?.let { "\n\n$it" } ?: "")
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
        else -> message ?: "The installation failed."
    }
}
