package com.example.gitsync

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Watches which app is in the foreground. When a user-selected "trigger app"
 * for ANY currently-running profile opens or closes, that specific profile's
 * sync is triggered immediately. Never reads screen content.
 */
class SyncAccessibilityService : AccessibilityService() {

    private lateinit var store: ProfileStore
    private var lastForegroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = ProfileStore(this)
        SyncStatusRegistry.init(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val newPackage = event.packageName?.toString() ?: return
        val previousPackage = lastForegroundPackage

        if (newPackage == previousPackage) return
        lastForegroundPackage = newPackage

        if (!::store.isInitialized) return
        val runningIds = store.runningProfileIds()
        if (runningIds.isEmpty()) return

        store.getAll()
            .filter { it.id in runningIds && it.triggerApps.isNotEmpty() }
            .forEach { profile ->
                val opened = profile.triggerApps.contains(newPackage)
                val closed = previousPackage != null && profile.triggerApps.contains(previousPackage)
                if (opened || closed) {
                    val label = if (opened) newPackage else previousPackage
                    SyncStatusRegistry.forProfile(profile.id)
                        .addLog(LogType.TRIGGER, "App trigger: $label ${if (opened) "opened" else "closed"}")
                    SyncService.triggerNow(this, profile.id)
                }
            }
    }

    override fun onInterrupt() {
        // No-op: nothing to clean up.
    }
}
