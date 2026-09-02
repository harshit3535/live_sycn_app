package com.example.gitsync

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GitHubSyncException(message: String) : Exception(message)

/**
 * Two-way sync against a GitHub repo using the Git Database REST API
 * (blobs/trees/commits/refs) — no local git binary needed.
 *
 * Conflict rule: if a file differs on both sides, the LOCAL (phone) copy wins.
 *
 * Delete propagation: a per-profile "manifest" (the set of paths seen at the
 * end of the last successful check) is kept on disk. If a path that WAS in
 * the manifest disappears from one side, that's treated as an intentional
 * deletion and is propagated to the other side. If a path was never in the
 * manifest (i.e. this is the first time either side has seen it), it's
 * treated as a new file and added, never deleted — this is what keeps the
 * very first sync of a non-empty folder safe.
 *
 * [onBranchResolved] is called once if the branch had to be auto-detected,
 * so the caller can persist it back onto the profile.
 */
class GitHubSyncEngine(
    private val profile: SyncProfile,
    private val manifestDir: File,
    private val onBranchResolved: (String) -> Unit = {}
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    data class RemoteEntry(val path: String, val sha: String, val size: Int)

    /**
     * Runs one full check+sync pass. Returns true if anything was actually
     * pushed, pulled, or deleted on either side; false if everything already matched.
     */
    fun syncOnce(onProgress: (SyncPhase, Int, String) -> Unit, onFileLogged: (LogType, String) -> Unit): Boolean {
        val (owner, repo) = profile.ownerRepo()
            ?: throw GitHubSyncException("Invalid repo URL")
        val token = profile.token
        if (token.isBlank()) throw GitHubSyncException("GitHub token missing")

        val rootDir = File(profile.folderPath)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            throw GitHubSyncException("Selected folder does not exist")
        }

        onProgress(SyncPhase.CHECKING, 0, "Checking for changes…")

        val branch = resolveBranch(owner, repo, token)
        val refSha = getRefSha(owner, repo, branch, token)
        val commitJson = getJson("https://api.github.com/repos/$owner/$repo/git/commits/$refSha", token)
        val rootTreeSha = commitJson.getJSONObject("tree").getString("sha")

        val remoteEntries = getTreeRecursive(owner, repo, rootTreeSha, token)
        val remoteByPath = remoteEntries.associateBy { it.path }
        val localFiles = listLocalFiles(rootDir)
        val previousManifest = loadManifest()

        val toPull = mutableListOf<RemoteEntry>()
        val toPush = mutableListOf<Pair<String, File>>()
        val toDeleteRemote = mutableListOf<String>()
        val toDeleteLocal = mutableListOf<String>()

        for (remote in remoteEntries) {
            val local = localFiles[remote.path]
            if (local == null) {
                if (remote.path in previousManifest) {
                    // Was synced before, now missing locally -> user deleted it -> delete on GitHub too.
                    toDeleteRemote.add(remote.path)
                } else {
                    toPull.add(remote)
                }
            } else {
                val localHash = GitBlobHasher.hashFile(local)
                if (localHash != remote.sha) {
                    toPush.add(remote.path to local) // differs on both sides -> local wins
                }
            }
        }
        for ((path, file) in localFiles) {
            if (!remoteByPath.containsKey(path)) {
                if (path in previousManifest) {
                    // Was synced before, now missing on GitHub -> deleted remotely -> delete locally too.
                    toDeleteLocal.add(path)
                } else {
                    toPush.add(path to file)
                }
            }
        }

        var somethingChanged = false

        if (toDeleteLocal.isNotEmpty()) {
            toDeleteLocal.forEachIndexed { index, path ->
                val percent = ((index + 1) * 100) / toDeleteLocal.size
                onProgress(SyncPhase.PULLING, percent, "Removing $path (deleted on GitHub)")
                File(rootDir, path).delete()
                onFileLogged(LogType.DELETE, "$path (removed locally — deleted on GitHub)")
            }
            somethingChanged = true
        }

        if (toPull.isNotEmpty()) {
            toPull.forEachIndexed { index, entry ->
                val percent = ((index + 1) * 100) / toPull.size
                onProgress(SyncPhase.PULLING, percent, "Pulling ${entry.path}")
                pullFile(owner, repo, entry, rootDir, token)
                onFileLogged(LogType.PULL, entry.path)
            }
            somethingChanged = true
        }

        if (toPush.isNotEmpty() || toDeleteRemote.isNotEmpty()) {
            pushChanges(owner, repo, branch, refSha, rootTreeSha, toPush, toDeleteRemote, token, onProgress)
            toPush.forEach { (path, _) -> onFileLogged(LogType.PUSH, path) }
            toDeleteRemote.forEach { path -> onFileLogged(LogType.DELETE, "$path (removed on GitHub — deleted locally)") }
            somethingChanged = true
        }

        // Refresh the manifest to the state now true on disk, so the next
        // check can correctly tell "new file" apart from "deleted file".
        val finalLocalPaths = (localFiles.keys - toDeleteLocal.toSet()) + toPull.map { it.path } + toPush.map { it.first }
        saveManifest(finalLocalPaths)

        if (!somethingChanged) {
            onProgress(SyncPhase.IDLE, 100, "Up to date")
            return false
        }

        onProgress(SyncPhase.IDLE, 100, "Sync complete")
        return true
    }

    private fun resolveBranch(owner: String, repo: String, token: String): String {
        val saved = profile.branch
        if (saved.isNotBlank()) return saved
        val repoJson = getJson("https://api.github.com/repos/$owner/$repo", token)
        val defaultBranch = repoJson.getString("default_branch")
        onBranchResolved(defaultBranch)
        return defaultBranch
    }

    private fun getRefSha(owner: String, repo: String, branch: String, token: String): String {
        val json = getJson("https://api.github.com/repos/$owner/$repo/git/ref/heads/$branch", token)
        return json.getJSONObject("object").getString("sha")
    }

    private fun getTreeRecursive(owner: String, repo: String, treeSha: String, token: String): List<RemoteEntry> {
        val json = getJson(
            "https://api.github.com/repos/$owner/$repo/git/trees/$treeSha?recursive=1",
            token
        )
        val array = json.getJSONArray("tree")
        val result = mutableListOf<RemoteEntry>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.optString("type") == "blob") {
                result.add(
                    RemoteEntry(
                        path = obj.getString("path"),
                        sha = obj.getString("sha"),
                        size = obj.optInt("size", 0)
                    )
                )
            }
        }
        return result
    }

    private fun pullFile(owner: String, repo: String, entry: RemoteEntry, rootDir: File, token: String) {
        val blobJson = getJson("https://api.github.com/repos/$owner/$repo/git/blobs/${entry.sha}", token)
        val content = blobJson.getString("content")
        val bytes = Base64.decode(content.replace("\n", ""), Base64.DEFAULT)
        val outFile = File(rootDir, entry.path)
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(bytes)
    }

    private fun pushChanges(
        owner: String,
        repo: String,
        branch: String,
        parentCommitSha: String,
        baseTreeSha: String,
        filesToUpsert: List<Pair<String, File>>,
        pathsToDelete: List<String>,
        token: String,
        onProgress: (SyncPhase, Int, String) -> Unit
    ) {
        val treeEntries = JSONArray()
        val totalOps = filesToUpsert.size + pathsToDelete.size
        var done = 0

        filesToUpsert.forEach { (path, file) ->
            done++
            onProgress(SyncPhase.PUSHING, (done * 100) / totalOps, "Pushing $path")
            val bytes = file.readBytes()
            val blobSha = createBlob(owner, repo, bytes, token)
            val entry = JSONObject()
            entry.put("path", path)
            entry.put("mode", "100644")
            entry.put("type", "blob")
            entry.put("sha", blobSha)
            treeEntries.put(entry)
        }

        pathsToDelete.forEach { path ->
            done++
            onProgress(SyncPhase.PUSHING, (done * 100) / totalOps, "Deleting $path on GitHub")
            val entry = JSONObject()
            entry.put("path", path)
            entry.put("mode", "100644")
            entry.put("type", "blob")
            entry.put("sha", JSONObject.NULL) // null sha = delete this path
            treeEntries.put(entry)
        }

        val treeBody = JSONObject().apply {
            put("base_tree", baseTreeSha)
            put("tree", treeEntries)
        }
        val newTreeSha = postJson(
            "https://api.github.com/repos/$owner/$repo/git/trees", token, treeBody
        ).getString("sha")

        val messageParts = mutableListOf<String>()
        if (filesToUpsert.isNotEmpty()) messageParts.add("update ${filesToUpsert.size} file(s)")
        if (pathsToDelete.isNotEmpty()) messageParts.add("delete ${pathsToDelete.size} file(s)")

        val commitBody = JSONObject().apply {
            put("message", "GitSync: ${messageParts.joinToString(", ")} from Android")
            put("tree", newTreeSha)
            put("parents", JSONArray().put(parentCommitSha))
        }
        val newCommitSha = postJson(
            "https://api.github.com/repos/$owner/$repo/git/commits", token, commitBody
        ).getString("sha")

        val refBody = JSONObject().apply {
            put("sha", newCommitSha)
            put("force", false)
        }
        patchJson(
            "https://api.github.com/repos/$owner/$repo/git/refs/heads/$branch", token, refBody
        )
    }

    private fun createBlob(owner: String, repo: String, bytes: ByteArray, token: String): String {
        val body = JSONObject().apply {
            put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("encoding", "base64")
        }
        val json = postJson("https://api.github.com/repos/$owner/$repo/git/blobs", token, body)
        return json.getString("sha")
    }

    // --- Low-level HTTP helpers -------------------------------------------------

    private fun authRequest(url: String, token: String): Request.Builder =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")

    private fun getJson(url: String, token: String): JSONObject {
        val request = authRequest(url, token).get().build()
        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw GitHubSyncException("GitHub API error ${response.code}: ${shortError(bodyStr)}")
            }
            return JSONObject(bodyStr)
        }
    }

    private fun postJson(url: String, token: String, body: JSONObject): JSONObject {
        val request = authRequest(url, token)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw GitHubSyncException("GitHub API error ${response.code}: ${shortError(bodyStr)}")
            }
            return JSONObject(bodyStr)
        }
    }

    private fun patchJson(url: String, token: String, body: JSONObject): JSONObject {
        val request = authRequest(url, token)
            .patch(body.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw GitHubSyncException("GitHub API error ${response.code}: ${shortError(bodyStr)}")
            }
            return JSONObject(bodyStr)
        }
    }

    private fun shortError(bodyStr: String): String = runCatching {
        JSONObject(bodyStr).optString("message", bodyStr)
    }.getOrDefault(bodyStr).take(200)

    private fun listLocalFiles(rootDir: File): Map<String, File> {
        val result = LinkedHashMap<String, File>()
        val rootPath = rootDir.path
        rootDir.walkTopDown()
            .onEnter { dir -> dir.name != ".git" && !dir.name.startsWith("_gitsync_backup_") }
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.path.removePrefix(rootPath).trimStart('/', '\\').replace('\\', '/')
                result[relative] = file
            }
        return result
    }

    // --- Manifest (tracks which paths were part of the last successful sync) ---

    private fun manifestFile(): File = File(manifestDir, "sync_manifest_${profile.id}.json")

    private fun loadManifest(): Set<String> {
        val f = manifestFile()
        if (!f.exists()) return emptySet()
        return try {
            val arr = JSONArray(f.readText())
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun saveManifest(paths: Collection<String>) {
        try {
            manifestFile().writeText(JSONArray(paths.toList()).toString())
        } catch (_: Exception) {
            // Best-effort; worst case the next check re-derives it safely.
        }
    }
}
