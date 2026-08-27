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
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var repo: EditText
    private lateinit var token: EditText
    private lateinit var folder: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPrefs(this)
        repo = findViewById(R.id.etRepo)
        token = findViewById(R.id.etToken)
        folder = findViewById(R.id.etFolder)
        status = findViewById(R.id.tvStatus)

        loadSavedSettings()
        requestNotificationPermission()

        findViewById<Button>(R.id.btnStart).setOnClickListener { startSync() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopSync() }

        status.text = prefs.status()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) status.text = prefs.status()
    }

    private fun loadSavedSettings() {
        repo.setText(prefs.repo())
        token.setText(prefs.token())
        folder.setText(
            prefs.folder().ifBlank {
                Environment.getExternalStorageDirectory().absolutePath + "/GitHubSync"
            }
        )
    }

    private fun startSync() {
        val repoUrl = repo.text.toString().trim()
        val accessToken = token.text.toString().trim()
        val path = folder.text.toString().trim()

        if (repoUrl.isBlank()) {
            repo.error = "Enter GitHub repository URL"
            return
        }
        if (accessToken.isBlank()) {
            token.error = "Enter GitHub token"
            return
        }
        if (path.isBlank()) {
            folder.error = "Enter folder path"
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, "Please allow All files access, then press Start Sync again.", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            return
        }

        prefs.saveSettings(repoUrl, accessToken, path)
        prefs.setStatus("🔄 Starting sync...")
        status.text = prefs.status()

        val serviceIntent = Intent(this, SyncService::class.java)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "GitSync started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            prefs.setStatus("❌ Could not start: ${e.message ?: "unknown error"}")
            status.text = prefs.status()
        }
    }

    private fun stopSync() {
        stopService(Intent(this, SyncService::class.java))
        prefs.setStatus("⛔ Sync stopped")
        status.text = prefs.status()
        Toast.makeText(this, "GitSync stopped", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
    }
}
