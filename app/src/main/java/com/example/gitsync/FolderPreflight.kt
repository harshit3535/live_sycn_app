package com.example.gitsync

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Used the first time a repo+folder pair is about to sync. Never deletes
 * anything — if the user chooses "fresh start", existing contents are moved
 * (not deleted) into a timestamped backup sub-folder so nothing is lost.
 */
object FolderPreflight {

    fun isEmpty(folderPath: String): Boolean {
        val dir = File(folderPath)
        if (!dir.exists()) return true
        val entries = dir.listFiles() ?: return true
        return entries.isEmpty()
    }

    /**
     * Moves everything currently at the top level of [folderPath] into a new
     * "_gitsync_backup_<timestamp>" sub-folder inside the same folder.
     * Returns the backup folder's path.
     */
    fun backupExistingContents(folderPath: String): String {
        val dir = File(folderPath)
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
        val backupDir = File(dir, "_gitsync_backup_$stamp")
        backupDir.mkdirs()
        dir.listFiles()?.forEach { entry ->
            if (entry.path == backupDir.path) return@forEach
            val dest = File(backupDir, entry.name)
            entry.renameTo(dest)
        }
        return backupDir.path
    }
}
