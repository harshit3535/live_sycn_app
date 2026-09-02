package com.example.gitsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts every profile that was running before the phone rebooted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = ProfileStore(context)
        store.runningProfileIds().forEach { profileId ->
            val profile = store.get(profileId)
            if (profile != null && profile.isConfigured()) {
                SyncService.start(context, profileId)
            }
        }
    }
}
