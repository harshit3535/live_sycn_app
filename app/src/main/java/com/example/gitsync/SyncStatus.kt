package com.example.gitsync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Live status + log for ONE profile. Each running profile gets its own
 * instance, so progress/log/counters never mix between profiles. The log
 * persists to its own small file so it survives the app being closed —
 * it's only erased when the user taps Clear for that specific profile.
 */
class ProfileStatus(private val profileId: String, appContext: Context) {

    private val logFile = File(appContext.filesDir, "sync_log_$profileId.txt")

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _phase = MutableStateFlow(SyncPhase.IDLE)
    val phase = _phase.asStateFlow()

    private val _progressPercent = MutableStateFlow(0)
    val progressPercent = _progressPercent.asStateFlow()

    private val _statusText = MutableStateFlow("Idle")
    val statusText = _statusText.asStateFlow()

    private val _lastSyncTimeMillis = MutableStateFlow(0L)
    val lastSyncTimeMillis = _lastSyncTimeMillis.asStateFlow()

    private val _nextCheckTimeMillis = MutableStateFlow(0L)
    val nextCheckTimeMillis = _nextCheckTimeMillis.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    init {
        loadLogsFromDisk()
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
        if (!running) {
            _phase.value = SyncPhase.IDLE
            _progressPercent.value = 0
            _statusText.value = "Stopped"
            _nextCheckTimeMillis.value = 0L
        }
    }

    fun setPhase(phase: SyncPhase, text: String) {
        _phase.value = phase
        _statusText.value = text
    }

    fun setProgress(percent: Int) {
        _progressPercent.value = percent.coerceIn(0, 100)
    }

    fun markCheck(nextCheckMillis: Long) {
        _nextCheckTimeMillis.value = nextCheckMillis
    }

    fun markSyncCompleted(timeMillis: Long) {
        _lastSyncTimeMillis.value = timeMillis
    }

    fun addLog(type: LogType, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), type, message)
        _logs.update { (it + entry).takeLast(MAX_LOG_ENTRIES) }
        try {
            logFile.appendText("${entry.timestampMillis}|${entry.type}|${entry.message}\n")
        } catch (_: Exception) {
            // Best-effort; UI already has it in memory for this session.
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        if (logFile.exists()) logFile.delete()
    }

    private fun loadLogsFromDisk() {
        if (!logFile.exists()) return
        try {
            _logs.value = logFile.readLines().mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size != 3) return@mapNotNull null
                val ts = parts[0].toLongOrNull() ?: return@mapNotNull null
                val type = runCatching { LogType.valueOf(parts[1]) }.getOrDefault(LogType.INFO)
                LogEntry(ts, type, parts[2])
            }.takeLast(MAX_LOG_ENTRIES)
        } catch (_: Exception) {
            // Ignore corrupt log file
        }
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 300
    }
}

/** App-wide (in-process) registry of per-profile statuses. */
object SyncStatusRegistry {

    private lateinit var appContext: Context
    private val map = ConcurrentHashMap<String, ProfileStatus>()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    fun forProfile(profileId: String): ProfileStatus =
        map.getOrPut(profileId) { ProfileStatus(profileId, appContext) }

    fun runningCount(): Int = map.values.count { it.isRunning.value }

    fun formatTime(millis: Long): String = if (millis <= 0) "Never" else formatter.format(millis)

    /** Called when a profile (template) is permanently deleted. */
    fun deleteProfileData(profileId: String) {
        map[profileId]?.clearLogs()
        map.remove(profileId)
    }
}
