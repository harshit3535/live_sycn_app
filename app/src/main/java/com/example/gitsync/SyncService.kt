package com.example.gitsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * One foreground service manages ALL running profiles at once (up to
 * [ProfileStore.MAX_RUNNING]). Each profile gets its own coroutine loop,
 * its own notification, and its own entry in [SyncStatusRegistry] — they
 * never share status/progress/log. Stopping one profile never affects
 * the others; the service itself only stops once none are running.
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loopJobs = ConcurrentHashMap<String, Job>()
    private val triggerChannels = ConcurrentHashMap<String, Channel<Unit>>()

    private lateinit var store: ProfileStore
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        store = ProfileStore(this)
        SyncStatusRegistry.init(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
        when (intent?.action) {
            ACTION_START -> {
                ensureForeground()
                if (profileId != null) startProfileLoop(profileId)
            }
            ACTION_STOP -> {
                if (profileId != null) stopProfileLoop(profileId)
            }
            ACTION_TRIGGER_NOW -> {
                ensureForeground()
                if (profileId != null) triggerChannels[profileId]?.trySend(Unit)
            }
            else -> ensureForeground()
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        startForeground(SUMMARY_NOTIFICATION_ID, buildSummaryNotification())
    }

    private fun startProfileLoop(profileId: String) {
        if (loopJobs[profileId]?.isActive == true) return // already running

        store.markRunning(profileId)
        val status = SyncStatusRegistry.forProfile(profileId)
        status.setRunning(true)

        val channel = Channel<Unit>(Channel.CONFLATED)
        triggerChannels[profileId] = channel

        val job = scope.launch {
            while (isActive) {
                val profile = store.get(profileId)
                if (profile == null) break // profile was deleted while running

                runSyncSafely(profile, status)

                val interval = store.get(profileId)?.interval ?: profile.interval
                val nextAt = System.currentTimeMillis() + interval.seconds * 1000L
                status.markCheck(nextAt)
                updateProfileNotification(profile, "Next check in ${interval.seconds}s", 0, false)

                withTimeoutOrNull(interval.seconds * 1000L) { channel.receive() }
            }
            // Loop ended (profile deleted) — clean up.
            cleanupAfterLoopEnd(profileId)
        }
        loopJobs[profileId] = job
        updateSummaryNotification()
    }

    private fun cleanupAfterLoopEnd(profileId: String) {
        loopJobs.remove(profileId)
        triggerChannels.remove(profileId)
        SyncStatusRegistry.forProfile(profileId).setRunning(false)
        notificationManager.cancel(profileNotificationId(profileId))
        updateSummaryNotification()
        stopServiceIfIdle()
    }

    private fun stopProfileLoop(profileId: String) {
        loopJobs[profileId]?.cancel()
        loopJobs.remove(profileId)
        triggerChannels.remove(profileId)
        store.markStopped(profileId)
        SyncStatusRegistry.forProfile(profileId).setRunning(false)
        notificationManager.cancel(profileNotificationId(profileId))
        updateSummaryNotification()
        stopServiceIfIdle()
    }

    private fun stopServiceIfIdle() {
        if (loopJobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun runSyncSafely(profile: SyncProfile, status: ProfileStatus) {
        try {
            if (!profile.isConfigured()) {
                status.addLog(LogType.ERROR, "Settings incomplete — check repo URL, token and folder")
                return
            }
            status.setPhase(SyncPhase.CHECKING, "Checking for changes…")
            updateProfileNotification(profile, "Checking for changes…", 0, false)

            val engine = GitHubSyncEngine(
                profile = profile,
                manifestDir = filesDir,
                onBranchResolved = { branch -> store.save(profile.copy(branch = branch)) }
            )

            val changed = engine.syncOnce(
                onProgress = { phase, percent, text ->
                    status.setPhase(phase, text)
                    status.setProgress(percent)
                    updateProfileNotification(profile, text, percent, phase == SyncPhase.PUSHING || phase == SyncPhase.PULLING)
                },
                onFileLogged = { type, path -> status.addLog(type, path) }
            )

            if (changed) {
                status.markSyncCompleted(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            status.setPhase(SyncPhase.ERROR, e.message ?: "Sync failed")
            status.addLog(LogType.ERROR, e.message ?: "Unknown error")
        } finally {
            status.setPhase(SyncPhase.IDLE, "Idle")
            status.setProgress(0)
        }
    }

    // --- Notifications -----------------------------------------------------

    private fun buildSummaryNotification(): Notification {
        val count = loopJobs.size
        val text = if (count == 0) "Idle" else "$count profile(s) syncing"
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("GitSync")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateSummaryNotification() {
        notificationManager.notify(SUMMARY_NOTIFICATION_ID, buildSummaryNotification())
    }

    private fun buildProfileNotification(profile: SyncProfile, text: String, percent: Int, showProgress: Boolean): Notification {
        val stopIntent = Intent(this, SyncService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_PROFILE_ID, profile.id)
        }
        val stopPending = PendingIntent.getService(
            this, profile.id.hashCode(), stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, profile.id.hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(profile.name)
            .setContentText(text)
            .setContentIntent(openPending)
            .addAction(0, "Stop", stopPending)
            .setOnlyAlertOnce(true)

        if (showProgress) {
            builder.setProgress(100, percent, false)
            builder.setContentText("$text — $percent%")
        }
        return builder.build()
    }

    private fun updateProfileNotification(profile: SyncProfile, text: String, percent: Int, showProgress: Boolean) {
        notificationManager.notify(profileNotificationId(profile.id), buildProfileNotification(profile, text, percent, showProgress))
    }

    private fun profileNotificationId(profileId: String): Int = 1000 + (profileId.hashCode() and 0xFFFF)

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "GitSync background sync", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows live sync status and progress per profile"
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        loopJobs.values.forEach { it.cancel() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.gitsync.action.START"
        const val ACTION_STOP = "com.example.gitsync.action.STOP"
        const val ACTION_TRIGGER_NOW = "com.example.gitsync.action.TRIGGER_NOW"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val CHANNEL_ID = "gitsync_channel"
        private const val SUMMARY_NOTIFICATION_ID = 1

        fun start(context: Context, profileId: String) {
            val intent = Intent(context, SyncService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROFILE_ID, profileId)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context, profileId: String) {
            val intent = Intent(context, SyncService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_PROFILE_ID, profileId)
            }
            context.startService(intent)
        }

        fun triggerNow(context: Context, profileId: String) {
            val intent = Intent(context, SyncService::class.java).apply {
                action = ACTION_TRIGGER_NOW
                putExtra(EXTRA_PROFILE_ID, profileId)
            }
            context.startForegroundService(intent)
        }
    }
}
