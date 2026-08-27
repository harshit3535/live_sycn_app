package com.example.gitsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SyncService : Service() {

    private lateinit var prefs: AppPrefs

    private val executor = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private var scheduled: ScheduledFuture<*>? = null
    private val running = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        createNotificationChannel()
        startForeground(1001, notification("Starting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runSyncNow()
        return START_STICKY
    }

    private fun runSyncNow() {
        if (!running.compareAndSet(false, true)) return

        executor.execute {
            try {
                val repoUrl = prefs.repo()
                val token = prefs.token()
                val folderPath = prefs.folder()

                if (repoUrl.isBlank() || token.isBlank() || folderPath.isBlank()) {
                    updateStatus("❌ Settings missing")
                    return@execute
                }

                val folder = File(folderPath)
                if (!folder.exists() && !folder.mkdirs()) {
                    updateStatus("❌ Cannot create folder")
                    return@execute
                }

                updateStatus("🔄 Checking GitHub...")

                val engine = GitHubSyncEngine(token)
                val result = engine.sync(repoUrl, folder, prefs)

                updateStatus(result)
                ensureSchedule()
            } catch (e: Exception) {
                e.printStackTrace()
                updateStatus("❌ ${e.message ?: "Sync failed"}")
                ensureSchedule()
            } finally {
                running.set(false)
            }
        }
    }

    private fun ensureSchedule() {
        if (scheduled?.isCancelled == false && scheduled?.isDone == false) return

        scheduled = scheduler.scheduleAtFixedRate(
            { runSyncNow() },
            120,
            120,
            TimeUnit.SECONDS
        )
    }

    private fun updateStatus(text: String) {
        prefs.setStatus(text)
        getSystemService(NotificationManager::class.java).notify(1001, notification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "GitSync",
                "GitSync",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        return NotificationCompat.Builder(this, "GitSync")
            .setContentTitle("GitSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scheduled?.cancel(true)
        scheduler.shutdownNow()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
