package com.nuvio.tv.relay.standalone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Receives the installer's progress for a self-update started by [AppUpdater]. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AppUpdater.ACTION_INSTALL_RESULT) return
        val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                AppUpdater.pendingConfirmation = confirm
                prefs.edit().putString("update_status", "Waiting for your confirmation on the TV").apply()
                if (AppUpdater.uiForeground && confirm != null) {
                    runCatching { context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppUpdater.pendingConfirmation = null
                prefs.edit().putString("update_status", "Updated successfully").apply()
            }
            else -> {
                AppUpdater.pendingConfirmation = null
                prefs.edit().putString("update_status", "Install failed" + if (message.isBlank()) "" else ": $message").apply()
            }
        }
    }
}
