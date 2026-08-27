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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etRepo = findViewById<EditText>(R.id.etRepo)
        val etToken = findViewById<EditText>(R.id.etToken)
        val etFolder = findViewById<EditText>(R.id.etFolder)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        etFolder.setText(Environment.getExternalStorageDirectory().absolutePath + "/GitHubSync")

        btnStart.setOnClickListener {
            val intent = Intent(this, SyncService::class.java).apply {
                putExtra("FOLDER_PATH", etFolder.text.toString())
                putExtra("REPO_URL", etRepo.text.toString())
                putExtra("TOKEN", etToken.text.toString())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            tvStatus.text = "✅ Running..."
            Toast.makeText(this, "Service Started", Toast.LENGTH_SHORT).show()
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, SyncService::class.java))
            tvStatus.text = "⛔ Stopped"
        }
    }
}
