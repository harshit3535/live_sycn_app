package com.example.gitsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ConnectionRecord(
    val key: String,
    val repoLabel: String,
    val folderPath: String,
    val initializedAtMillis: Long
)

/**
 * Tracks which repo+folder pairs have already gone through the first-sync
 * safety check, independent of whether the profile that created them still
 * exists. Kept separate on purpose so the user can review/clear it on its own.
 */
class ConnectionsStore(context: Context) {

    private val prefs = context.getSharedPreferences("gitsync_connections", Context.MODE_PRIVATE)

    fun isInitialized(key: String): Boolean = getAll().any { it.key == key }

    fun markInitialized(key: String, repoLabel: String, folderPath: String) {
        val all = getAll().filterNot { it.key == key }.toMutableList()
        all.add(ConnectionRecord(key, repoLabel, folderPath, System.currentTimeMillis()))
        persist(all)
    }

    fun remove(key: String) {
        persist(getAll().filterNot { it.key == key })
    }

    fun clearAll() {
        prefs.edit().remove(KEY).apply()
    }

    fun getAll(): List<ConnectionRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val list = mutableListOf<ConnectionRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                ConnectionRecord(
                    key = o.getString("key"),
                    repoLabel = o.optString("repoLabel", ""),
                    folderPath = o.optString("folderPath", ""),
                    initializedAtMillis = o.optLong("at", 0L)
                )
            )
        }
        return list.sortedByDescending { it.initializedAtMillis }
    }

    private fun persist(list: List<ConnectionRecord>) {
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject()
            o.put("key", it.key)
            o.put("repoLabel", it.repoLabel)
            o.put("folderPath", it.folderPath)
            o.put("at", it.initializedAtMillis)
            arr.put(o)
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "connections_json"
    }
}
