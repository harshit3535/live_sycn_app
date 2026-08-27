package com.example.gitsync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etRepo: EditText
    private lateinit var etToken: EditText
    private lateinit var etFolder: EditText
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        etRepo = findViewById(R.id.etRepo)
        etToken = findViewById(R.id.etToken)
        etFolder = findViewById(R.id.etFolder)

        val btnStart =
            findViewById<Button>(R.id.btnStart)

        val btnStop =
            findViewById<Button>(R.id.btnStop)

        tvStatus =
            findViewById(R.id.tvStatus)

        etFolder.setText(
            "${Environment.getExternalStorageDirectory().absolutePath}/GitHubSync"
        )

        requestNotificationPermission()

        btnStart.setOnClickListener {
            startSync()
        }

        btnStop.setOnClickListener {
            stopSync()
        }
    }

    private fun startSync() {

        val repoUrl = etRepo.text.toString().trim()
        val token = etToken.text.toString().trim()
        val folder = etFolder.text.toString().trim()

        if (repoUrl.isEmpty()) {
            etRepo.error = "Enter GitHub repository URL"
            return
        }

        if (token.isEmpty()) {
            etToken.error = "Enter GitHub token"
            return
        }

        if (folder.isEmpty()) {
            etFolder.error = "Enter folder path"
            return
        }

        val intent =
            Intent(
                this,
                SyncService::class.java
            ).apply {

                putExtra(
                    "FOLDER_PATH",
                    folder
                )

                putExtra(
                    "REPO_URL",
                    repoUrl
                )

                putExtra(
                    "TOKEN",
                    token
                )
            }

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                startForegroundService(intent)

            } else {

                startService(intent)
            }

            tvStatus.text = "✅ Sync Starting..."

            Toast.makeText(
                this,
                "GitSync started",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            e.printStackTrace()

            tvStatus.text = "❌ Failed to start"

            Toast.makeText(
                this,
                e.message ?: "Unable to start service",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopSync() {

        stopService(
            Intent(
                this,
                SyncService::class.java
            )
        )

        tvStatus.text = "⛔ Sync Stopped"

        Toast.makeText(
            this,
            "GitSync stopped",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun requestNotificationPermission() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            if (
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.POST_NOTIFICATIONS
                    ),
                    100
                )
            }
        }
    }
}
