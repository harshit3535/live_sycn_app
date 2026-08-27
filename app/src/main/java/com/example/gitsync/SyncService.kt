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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class SyncService : Service() {

    private lateinit var gitManager: GitManager

    private var fileObservers = mutableListOf<FileObserver>()

    private val executor = Executors.newSingleThreadExecutor()

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()

    private var folderPath: String = ""

    override fun onCreate() {
        super.onCreate()

        startForeground(
            1001,
            createNotification("Starting GitHub Sync...")
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val folder = intent?.getStringExtra("FOLDER_PATH")
        val url = intent?.getStringExtra("REPO_URL")
        val token = intent?.getStringExtra("TOKEN")

        if (folder.isNullOrBlank() ||
            url.isNullOrBlank() ||
            token.isNullOrBlank()
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        folderPath = folder

        executor.execute {

            try {

                val repoDir = File(folderPath)

                if (!repoDir.exists()) {
                    repoDir.mkdirs()
                }

                gitManager = GitManager(
                    repoDir,
                    token
                )

                if (!gitManager.isRepository()) {

                    val cloned = gitManager.cloneRepo(url)

                    if (!cloned) {
                        updateNotification("Clone failed")
                        stopSelf()
                        return@execute
                    }
                }

                updateNotification("Sync Active")

                startFileObservers(repoDir)

                startPeriodicPull()

            } catch (e: Exception) {
                e.printStackTrace()
                updateNotification("Sync Error")
            }
        }

        return START_STICKY
    }

    private fun startFileObservers(root: File) {

        stopFileObservers()

        observeDirectory(root)

        root.walkTopDown()
            .filter { it.isDirectory }
            .forEach { directory ->

                if (directory.absolutePath != root.absolutePath) {
                    observeDirectory(directory)
                }
            }
    }

    private fun observeDirectory(directory: File) {

        try {

            val observer = object : FileObserver(directory.absolutePath) {

                override fun onEvent(
                    event: Int,
                    path: String?
                ) {

                    val importantEvents =
                        FileObserver.CREATE or
                        FileObserver.CLOSE_WRITE or
                        FileObserver.MOVED_TO or
                        FileObserver.MOVED_FROM or
                        FileObserver.DELETE

                    if ((event and importantEvents) == 0) {
                        return
                    }

                    executor.execute {

                        try {

                            if (::gitManager.isInitialized) {
                                updateNotification("Uploading changes...")
                                gitManager.push()
                                updateNotification("Sync Active")
                            }

                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            observer.startWatching()

            fileObservers.add(observer)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startPeriodicPull() {

        scheduler.scheduleWithFixedDelay(

            {
                executor.execute {

                    try {

                        if (::gitManager.isInitialized) {

                            updateNotification("Checking GitHub...")

                            gitManager.pull()

                            updateNotification("Sync Active")
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                        updateNotification("Pull failed")
                    }
                }
            },

            5,
            120,
            TimeUnit.SECONDS
        )
    }

    private fun stopFileObservers() {

        for (observer in fileObservers) {
            try {
                observer.stopWatching()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fileObservers.clear()
    }

    private fun updateNotification(text: String) {

        val notificationManager =
            getSystemService(NotificationManager::class.java)

        notificationManager.notify(
            1001,
            createNotification(text)
        )
    }

    private fun createNotification(
        text: String
    ): Notification {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                "GitSync",
                "GitHub Sync",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(
            this,
            "GitSync"
        )
            .setContentTitle("GitSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {

        stopFileObservers()

        scheduler.shutdownNow()
        executor.shutdownNow()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
