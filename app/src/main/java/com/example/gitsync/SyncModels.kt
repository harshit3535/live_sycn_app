package com.example.gitsync

/**
 * How often the background service checks local + GitHub for changes.
 * A sync (push/pull) only actually happens if a difference is found —
 * the value below is just how often that check runs.
 */
enum class SyncInterval(val seconds: Int, val label: String) {
    ON_CHANGE(60, "On change (checks every 1 min)"),
    SEC_30(30, "Every 30 seconds"),
    MIN_1(60, "Every 1 minute"),
    MIN_2(120, "Every 2 minutes"),
    MIN_3(180, "Every 3 minutes"),
    MIN_5(300, "Every 5 minutes"),
    MIN_10(600, "Every 10 minutes");

    companion object {
        fun fromName(name: String?): SyncInterval =
            entries.firstOrNull { it.name == name } ?: ON_CHANGE
    }
}

enum class LogType { INFO, PUSH, PULL, DELETE, ERROR, TRIGGER }

data class LogEntry(
    val timestampMillis: Long,
    val type: LogType,
    val message: String
)

/** A simple installed-app entry used by the app picker for open/close triggers. */
data class InstalledAppInfo(
    val packageName: String,
    val label: String
)

enum class SyncPhase { IDLE, CHECKING, PUSHING, PULLING, ERROR }

/**
 * A saved sync configuration ("template"). Unlimited templates can exist;
 * only up to [ProfileStore.MAX_RUNNING] can be actively syncing at once.
 */
data class SyncProfile(
    val id: String,
    val name: String,
    val repoUrl: String,
    val token: String,
    val folderPath: String,
    val branch: String = "",
    val interval: SyncInterval = SyncInterval.ON_CHANGE,
    val triggerApps: Set<String> = emptySet()
) {
    /** Extracted "owner" to "repo" from the repo URL, or null if invalid. */
    fun ownerRepo(): Pair<String, String>? {
        val cleaned = repoUrl.trim().removeSuffix(".git").removeSuffix("/")
        val regex = Regex("""github\.com[:/]+([^/]+)/([^/]+)$""")
        val match = regex.find(cleaned) ?: return null
        val (owner, repo) = match.destructured
        return owner to repo
    }

    fun isConfigured(): Boolean =
        repoUrl.isNotBlank() && token.isNotBlank() && folderPath.isNotBlank() && ownerRepo() != null

    /** Identifies a specific repo+folder pairing, used to track first-sync safety/initialization. */
    fun connectionKey(): String {
        val (owner, repo) = ownerRepo() ?: ("unknown" to "unknown")
        return "$owner/$repo|$folderPath"
    }
}
