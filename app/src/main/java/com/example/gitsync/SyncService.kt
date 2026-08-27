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

    private val syncExecutor =
        Executors.newSingleThreadExecutor()

    private val scheduler: ScheduledExecutorService =
        Executors.newScheduledThreadPool(2)

    private val observers =
        ConcurrentHashMap<String, FileObserver>()

    private val syncing =
        AtomicBoolean(false)

    private var pendingPush: ScheduledFuture<*>? = null

    private var repoDir: File? = null

    override fun onCreate() {
        super.onCreate()

        prefs = AppPrefs(this)

        createNotificationChannel()

        startForeground(
            1001,
            createNotification("Starting...")
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val folder =
            intent?.getStringExtra("FOLDER_PATH")
                ?.trim()
                .orEmpty()
                .ifBlank {
                    prefs.getFolderPath()
                }

        val repoUrl =
            intent?.getStringExtra("REPO_URL")
                ?.trim()
                .orEmpty()
                .ifBlank {
                    prefs.getRepoUrl()
                }

        val token =
            intent?.getStringExtra("TOKEN")
                ?.trim()
                .orEmpty()
                .ifBlank {
                    prefs.getToken()
                }

        if (
            folder.isBlank() ||
            repoUrl.isBlank() ||
            token.isBlank()
        ) {
            updateNotification(
                "❌ Settings missing"
            )

            stopSelf()

            return START_NOT_STICKY
        }

        prefs.saveSettings(
            repoUrl,
            token,
            folder
        )

        syncExecutor.execute {
            initializeAndSync(
                folder,
                repoUrl,
                token
            )
        }

        return START_STICKY
    }

    private fun initializeAndSync(
        folder: String,
        repoUrl: String,
        token: String
    ) {

        try {

            val directory = File(folder)

            if (!directory.exists()) {
                directory.mkdirs()
            }

            repoDir = directory

            gitManager =
                GitManager(
                    directory,
                    token
                )

            updateNotification(
                "🔗 Connecting to GitHub..."
            )

            if (!gitManager.isRepository()) {

                updateNotification(
                    "⬇️ Cloning repository..."
                )

                val cloned =
                    gitManager.cloneRepo(repoUrl)

                if (!cloned) {

                    updateNotification(
                        "❌ Clone failed"
                    )

                    return
                }
            }

            /*
             * Pull first so the phone starts
             * from the current GitHub state.
             */
            updateNotification(
                "⬇️ Syncing from GitHub..."
            )

            val pulled =
                gitManager.pull()

            if (!pulled) {

                updateNotification(
                    "❌ Initial pull failed"
                )

                return
            }

            updateNotification(
                "✅ Sync Active"
            )

            watchDirectoryTree(directory)

            startPeriodicPull()

        } catch (e: Exception) {

            e.printStackTrace()

            updateNotification(
                "❌ Sync error: ${e.message ?: "Unknown error"}"
            )
        }
    }

    private fun watchDirectoryTree(
        root: File
    ) {

        stopAllObservers()

        try {

            root.walkTopDown()
                .filter {
                    it.isDirectory
                }
                .forEach {
                    watchDirectory(it)
                }

        } catch (e: Exception) {

            e.printStackTrace()
        }
    }

    private fun watchDirectory(
        directory: File
    ) {

        val path =
            directory.absolutePath

        if (observers.containsKey(path)) {
            return
        }

        try {

            val observer =
                object : FileObserver(
                    path,
                    FileObserver.CREATE or
                            FileObserver.CLOSE_WRITE or
                            FileObserver.MOVED_TO or
                            FileObserver.MOVED_FROM or
                            FileObserver.DELETE or
                            FileObserver.DELETE_SELF
                ) {

                    override fun onEvent(
                        event: Int,
                        relativePath: String?
                    ) {

                        /*
                         * Ignore events while Git is
                         * performing its own pull/push.
                         */
                        if (syncing.get()) {
                            return
                        }

                        val relevantEvents =
                            FileObserver.CREATE or
                                    FileObserver.CLOSE_WRITE or
                                    FileObserver.MOVED_TO or
                                    FileObserver.MOVED_FROM or
                                    FileObserver.DELETE

                        if (
                            (event and relevantEvents) == 0
                        ) {
                            return
                        }

                        /*
                         * A new directory can appear after
                         * the observer tree was created.
                         *
                         * We do not use FileObserver.ISDIR
                         * because that constant does not exist.
                         */
                        if (relativePath != null) {

                            val changedPath =
                                File(
                                    directory,
                                    relativePath
                                )

                            if (
                                changedPath.isDirectory
                            ) {
                                repoDir?.let {
                                    watchDirectoryTree(it)
                                }
                            }
                        }

                        schedulePush()
                    }
                }

            observer.startWatching()

            observers[path] =
                observer

        } catch (e: Exception) {

            e.printStackTrace()
        }
    }

    private fun schedulePush() {

        pendingPush?.cancel(false)

        pendingPush =
            scheduler.schedule(
                {
                    syncExecutor.execute {
                        pushChanges()
                    }
                },
                1,
                TimeUnit.SECONDS
            )
    }

    private fun pushChanges() {

        if (
            syncing.getAndSet(true)
        ) {
            return
        }

        try {

            updateNotification(
                "⬆️ Uploading local changes..."
            )

            val success =
                gitManager.push()

            if (success) {

                updateNotification(
                    "✅ Sync Active"
                )

            } else {

                updateNotification(
                    "❌ Push failed"
                )
            }

        } catch (e: Exception) {

            e.printStackTrace()

            updateNotification(
                "❌ Push error: ${
                    e.message ?: "Unknown error"
                }"
            )

        } finally {

            syncing.set(false)
        }
    }

    private fun startPeriodicPull() {

        scheduler.scheduleWithFixedDelay(

            {
                syncExecutor.execute {
                    pullChanges()
                }
            },

            120,
            120,
            TimeUnit.SECONDS
        )
    }

    private fun pullChanges() {

        if (
            syncing.getAndSet(true)
        ) {
            return
        }

        try {

            updateNotification(
                "⬇️ Checking GitHub..."
            )

            val success =
                gitManager.pull()

            if (success) {

                updateNotification(
                    "✅ Sync Active"
                )

                repoDir?.let {
                    watchDirectoryTree(it)
                }

            } else {

                updateNotification(
                    "❌ Pull failed"
                )
            }

        } catch (e: Exception) {

            e.printStackTrace()

            updateNotification(
                "❌ Pull error: ${
                    e.message ?: "Unknown error"
                }"
            )

        } finally {

            syncing.set(false)
        }
    }

    private fun updateNotification(
        text: String
    ) {

        getSystemService(
            NotificationManager::class.java
        ).notify(
            1001,
            createNotification(text)
        )
    }

    private fun createNotification(
        text: String
    ): Notification {

        return NotificationCompat.Builder(
            this,
            "GitSync"
        )
            .setContentTitle(
                "🔄 GitHub Sync"
            )
            .setContentText(text)
            .setSmallIcon(
                android.R.drawable.ic_popup_sync
            )
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    "GitSync",
                    "GitHub Sync",
                    NotificationManager.IMPORTANCE_LOW
                )

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    private fun stopAllObservers() {

        observers.values.forEach {

            try {
                it.stopWatching()
            } catch (_: Exception) {
            }
        }

        observers.clear()
    }

    override fun onDestroy() {

        pendingPush?.cancel(true)

        stopAllObservers()

        scheduler.shutdownNow()
        syncExecutor.shutdownNow()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }
}
