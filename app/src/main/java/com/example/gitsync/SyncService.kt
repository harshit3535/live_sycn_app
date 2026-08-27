package com.example.gitsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SyncService : Service() {

    private lateinit var prefs: AppPrefs
    private lateinit var gitManager: GitManager

    private val syncExecutor = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private val observers = ConcurrentHashMap<String, FileObserver>()
    private val syncing = AtomicBoolean(false)

    private var pendingPush: ScheduledFuture<*>? = null
    private var periodicPull: ScheduledFuture<*>? = null
    private var repoDir: File? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        createNotificationChannel()
        startForeground(1001, createNotification("Starting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val folder = intent?.getStringExtra("FOLDER_PATH")?.trim().orEmpty()
            .ifBlank { prefs.getFolderPath() }
        val repoUrl = intent?.getStringExtra("REPO_URL")?.trim().orEmpty()
            .ifBlank { prefs.getRepoUrl() }
        val token = intent?.getStringExtra("TOKEN")?.trim().orEmpty()
            .ifBlank { prefs.getToken() }

        if (folder.isBlank() || repoUrl.isBlank() || token.isBlank()) {
            updateNotification("❌ Settings missing")
            stopSelf()
            return START_NOT_STICKY
        }

        prefs.saveSettings(repoUrl, token, folder)

        if (syncing.compareAndSet(false, true)) {
            syncExecutor.execute {
                initialize(folder, repoUrl, token)
                syncing.set(false)
            }
        }

        return START_STICKY
    }

    private fun initialize(folder: String, repoUrl: String, token: String) {
        try {
            val directory = File(folder)
            if (!directory.exists()) directory.mkdirs()
            repoDir = directory
            gitManager = GitManager(directory, token)

            updateNotification("🔗 Connecting to GitHub...")

            if (!gitManager.isRepository()) {
                updateNotification("⬇️ Cloning repository...")
                if (!gitManager.cloneRepo(repoUrl)) {
                    updateNotification("❌ Clone failed")
                    return
                }
            }

            updateNotification("✅ Sync Active")
            watchDirectoryTree(directory)
            startPeriodicPull()
        } catch (e: Exception) {
            e.printStackTrace()
            updateNotification("❌ Sync error")
        }
    }

    private fun watchDirectoryTree(root: File) {
        stopAllObservers()
        try {
            root.walkTopDown()
                .filter { it.isDirectory }
                .forEach { watchDirectory(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun watchDirectory(directory: File) {
        val path = directory.absolutePath
        if (observers.containsKey(path)) return

        try {
            val observer = object : FileObserver(
                path,
                FileObserver.CREATE or
                    FileObserver.CLOSE_WRITE or
                    FileObserver.MOVED_TO or
                    FileObserver.MOVED_FROM or
                    FileObserver.DELETE or
                    FileObserver.DELETE_SELF
            ) {
                override fun onEvent(event: Int, relativePath: String?) {
                    if (syncing.get()) return

                    val relevant = FileObserver.CREATE or
                        FileObserver.CLOSE_WRITE or
                        FileObserver.MOVED_TO or
                        FileObserver.MOVED_FROM or
                        FileObserver.DELETE

                    if ((event and relevant) == 0) return
                    schedulePush()

                    if (relativePath != null &&
                        (event and FileObserver.ISDIR) != 0 &&
                        (event and FileObserver.CREATE) != 0
                    ) {
                        repoDir?.let { watchDirectoryTree(it) }
                    }
                }
            }

            observer.startWatching()
            observers[path] = observer
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun schedulePush() {
        pendingPush?.cancel(false)
        pendingPush = scheduler.schedule({
            syncExecutor.execute { pushChanges() }
        }, 1, TimeUnit.SECONDS)
    }

    private fun pushChanges() {
        if (!syncing.compareAndSet(false, true)) return

        try {
            updateNotification("⬆️ Pushing changes...")
            val success = gitManager.push()
            updateNotification(if (success) "✅ Sync Active" else "❌ Push failed")
        } catch (e: Exception) {
            e.printStackTrace()
            updateNotification("❌ Push error")
        } finally {
            syncing.set(false)
        }
    }

    private fun startPeriodicPull() {
        periodicPull?.cancel(false)
        periodicPull = scheduler.scheduleWithFixedDelay({
            syncExecutor.execute { pullChanges() }
        }, 5, 120, TimeUnit.SECONDS)
    }

    private fun pullChanges() {
        if (!syncing.compareAndSet(false, true)) return

        try {
            updateNotification("⬇️ Checking GitHub...")
            val success = gitManager.pull()
            if (success) {
                updateNotification("✅ Sync Active")
                repoDir?.let { watchDirectoryTree(it) }
            } else {
                updateNotification("❌ Pull failed")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            updateNotification("❌ Pull error")
        } finally {
            syncing.set(false)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            1001,
            createNotification(text)
        )
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "GitSync")
            .setContentTitle("🔄 GitHub Sync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "GitSync",
                "GitHub Sync",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun stopAllObservers() {
        observers.values.forEach {
            try { it.stopWatching() } catch (_: Exception) { }
        }
        observers.clear()
    }

    override fun onDestroy() {
        pendingPush?.cancel(true)
        periodicPull?.cancel(true)
        stopAllObservers()
        scheduler.shutdownNow()
        syncExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
