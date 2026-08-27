package com.example.gitsync

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var etRepo: EditText
    private lateinit var etToken: EditText
    private lateinit var etFolder: EditText
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPrefs(this)

        etRepo = findViewById(R.id.etRepo)
        etToken = findViewById(R.id.etToken)
        etFolder = findViewById(R.id.etFolder)
        tvStatus = findViewById(R.id.tvStatus)

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)

        loadSavedSettings()

        btnStart.setOnClickListener { startSync() }
        btnStop.setOnClickListener { stopSync() }
    }

    private fun loadSavedSettings() {
        val savedRepo = prefs.getRepoUrl()
        val savedToken = prefs.getToken()
        val savedFolder = prefs.getFolderPath()

        if (savedRepo.isNotBlank()) etRepo.setText(savedRepo)
        if (savedToken.isNotBlank()) etToken.setText(savedToken)

        if (savedFolder.isNotBlank()) {
            etFolder.setText(savedFolder)
        } else {
            etFolder.setText(
                "${Environment.getExternalStorageDirectory().absolutePath}/GitHubSync"
            )
        }
    }

    private fun startSync() {
        val repoUrl = etRepo.text.toString().trim()
        val token = etToken.text.toString().trim()
        val folder = etFolder.text.toString().trim()

        if (repoUrl.isBlank()) {
            etRepo.error = "Enter GitHub repository URL"
            return
        }

        if (token.isBlank()) {
            etToken.error = "Enter GitHub token"
            return
        }

        if (folder.isBlank()) {
            etFolder.error = "Enter folder path"
            return
        }

        prefs.saveSettings(repoUrl, token, folder)

        val intent = Intent(this, SyncService::class.java).apply {
            putExtra("FOLDER_PATH", folder)
            putExtra("REPO_URL", repoUrl)
            putExtra("TOKEN", token)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            tvStatus.text = "🔄 Sync Starting..."
            Toast.makeText(this, "GitSync started", Toast.LENGTH_SHORT).show()
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
        stopService(Intent(this, SyncService::class.java))
        tvStatus.text = "⛔ Sync Stopped"
        Toast.makeText(this, "GitSync stopped", Toast.LENGTH_SHORT).show()
    }
}
