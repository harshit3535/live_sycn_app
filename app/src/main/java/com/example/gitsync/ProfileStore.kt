package com.example.gitsync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores all saved profiles ("templates") and which profile IDs are
 * currently meant to be running (used to restore sync after a reboot,
 * and to enforce the max-simultaneous-running limit).
 */
class ProfileStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "gitsync_profiles_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getAll(): List<SyncProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val list = mutableListOf<SyncProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val triggerApps = mutableSetOf<String>()
            o.optJSONArray("triggerApps")?.let { ta ->
                for (j in 0 until ta.length()) triggerApps.add(ta.getString(j))
            }
            list.add(
                SyncProfile(
                    id = o.getString("id"),
                    name = o.optString("name", "Untitled"),
                    repoUrl = o.optString("repoUrl", ""),
                    token = o.optString("token", ""),
                    folderPath = o.optString("folderPath", ""),
                    branch = o.optString("branch", ""),
                    interval = SyncInterval.fromName(o.optString("interval", SyncInterval.ON_CHANGE.name)),
                    triggerApps = triggerApps
                )
            )
        }
        return list
    }

    fun get(profileId: String): SyncProfile? = getAll().firstOrNull { it.id == profileId }

    fun save(profile: SyncProfile) {
        val all = getAll().toMutableList()
        val idx = all.indexOfFirst { it.id == profile.id }
        if (idx >= 0) all[idx] = profile else all.add(profile)
        persist(all)
    }

    fun delete(profileId: String) {
        persist(getAll().filterNot { it.id == profileId })
        setRunningProfileIds(runningProfileIds() - profileId)
    }

    private fun persist(list: List<SyncProfile>) {
        val arr = JSONArray()
        list.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("repoUrl", p.repoUrl)
            o.put("token", p.token)
            o.put("folderPath", p.folderPath)
            o.put("branch", p.branch)
            o.put("interval", p.interval.name)
            o.put("triggerApps", JSONArray(p.triggerApps.toList()))
            arr.put(o)
        }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    /** IDs the user has pressed Start on and not yet Stop — used to survive reboots. */
    fun runningProfileIds(): Set<String> {
        val raw = prefs.getString(KEY_RUNNING, null) ?: return emptySet()
        val arr = JSONArray(raw)
        val set = mutableSetOf<String>()
        for (i in 0 until arr.length()) set.add(arr.getString(i))
        return set
    }

    fun setRunningProfileIds(ids: Set<String>) {
        prefs.edit().putString(KEY_RUNNING, JSONArray(ids.toList()).toString()).apply()
    }

    fun markRunning(profileId: String) {
        setRunningProfileIds(runningProfileIds() + profileId)
    }

    fun markStopped(profileId: String) {
        setRunningProfileIds(runningProfileIds() - profileId)
    }

    companion object {
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_RUNNING = "running_ids_json"

        /** Max number of profiles allowed to sync at the same time. */
        const val MAX_RUNNING = 4
    }
}
