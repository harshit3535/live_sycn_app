package com.example.gitsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File

class SyncService : Service() {
    private lateinit var gitManager: GitManager
    private val handler = Handler(Looper.getMainLooper())
    private var pullRunnable: Runnable? = null
    private var fileObserver: FileObserver? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val folder = intent?.getStringExtra("FOLDER_PATH") ?: return START_NOT_STICKY
        val url = intent.getStringExtra("REPO_URL") ?: return START_NOT_STICKY
        val token = intent.getStringExtra("TOKEN") ?: return START_NOT_STICKY

        val repoDir = File(folder)
        if (!repoDir.exists()) repoDir.mkdirs()
        gitManager = GitManager(repoDir, token)
        if (!File(repoDir, ".git").exists()) gitManager.cloneRepo(url)

        startForeground(1001, createNotification("Sync Active"))
        startFileObserver(folder)
        startPeriodicPull()
        return START_STICKY
    }

    private fun startFileObserver(path: String) {
        fileObserver?.stopWatching()
        fileObserver = object : FileObserver(path, FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.MOVED_TO) {
            override fun onEvent(event: Int, relativePath: String?) {
                handler.postDelayed({ if (::gitManager.isInitialized) gitManager.push() }, 1000)
            }
        }
        fileObserver?.startWatching()
    }

    private fun startPeriodicPull() {
        pullRunnable?.let { handler.removeCallbacks(it) }
        pullRunnable = object : Runnable {
            override fun run() {
                if (::gitManager.isInitialized) gitManager.pull()
                handler.postDelayed(this, 120000)
            }
        }
        handler.postDelayed(pullRunnable!!, 5000)
    }

    private fun createNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "GitSync",
                "GitHub Sync",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, "GitSync")
            .setContentTitle("🔄 GitHub Sync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        fileObserver?.stopWatching()
        fileObserver = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
