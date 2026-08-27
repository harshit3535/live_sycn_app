package com.example.gitsync

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class GitHubSyncEngine(private val token: String) {

    private data class RemoteRepo(
        val owner: String,
        val name: String,
        val branch: String,
        val headSha: String,
        val treeSha: String,
        val files: MutableMap<String, String>
    )

    private data class LocalFile(
        val file: File,
        val gitSha: String
    )

    fun sync(repoUrl: String, folder: File, prefs: AppPrefs): String {
        require(token.isNotBlank()) { "GitHub token is empty" }
        require(repoUrl.isNotBlank()) { "Repository URL is empty" }

        if (!folder.exists() && !folder.mkdirs()) {
            throw IllegalStateException("Cannot create folder: ${folder.absolutePath}")
        }
        if (!folder.isDirectory) {
            throw IllegalStateException("Folder path is not a directory")
        }

        val repo = parseRepo(repoUrl)
        val branch = prefs.branch().ifBlank {
            val apiRepo = getDefaultBranch(repo.first, repo.second)
            apiRepo.getString("default_branch", "main")
        }
        prefs.setBranch(branch)

        var remote = fetchRemote(repo.first, repo.second, branch)
        var local = scanLocal(folder)

        val previousLocal = parseSnapshot(prefs.localSnapshot())
        val previousRemote = parseSnapshot(prefs.remoteSnapshot())

        if (previousLocal.isEmpty() && previousRemote.isEmpty()) {
            val result = firstSync(folder, local, remote)
            remote = fetchRemote(repo.first, repo.second, branch)
            local = scanLocal(folder)
            prefs.saveSnapshot(localSnapshotJson(local), remoteSnapshotJson(remote.files), remote.headSha)
            return result
        }

        val allPaths = linkedSetOf<String>()
        allPaths.addAll(previousLocal.keys)
        allPaths.addAll(previousRemote.keys)
        allPaths.addAll(local.keys)
        allPaths.addAll(remote.files.keys)

        val localChanged = mutableSetOf<String>()
        val remoteChanged = mutableSetOf<String>()

        for (path in allPaths) {
            val oldLocal = previousLocal[path]
            val oldRemote = previousRemote[path]
            val nowLocal = local[path]
            val nowRemote = remote.files[path]

            if (nowLocal != oldLocal) localChanged.add(path)
            if (nowRemote != oldRemote) remoteChanged.add(path)
        }

        val remoteOnly = remoteChanged - localChanged
        val localWinning = localChanged

        if (remoteOnly.isNotEmpty()) {
            pullRemoteChanges(folder, remote, remoteOnly)
        }

        local = scanLocal(folder)

        if (localWinning.isNotEmpty()) {
            val changedForPush = localWinning.associateWith { local[it]?.gitSha }
            remote = pushLocalChanges(repo.first, repo.second, branch, remote, changedForPush, folder)
        }

        local = scanLocal(folder)
        prefs.saveSnapshot(localSnapshotJson(local), remoteSnapshotJson(remote.files), remote.headSha)

        return buildString {
            if (localWinning.isEmpty() && remoteOnly.isEmpty()) {
                append("✅ Already in sync")
            } else {
                if (remoteOnly.isNotEmpty()) append("⬇️ ${remoteOnly.size} remote change(s)")
                if (localWinning.isNotEmpty()) {
                    if (isNotEmpty()) append(" • ")
                    append("⬆️ ${localWinning.size} local change(s)")
                }
                append(" • ✅ Synced")
            }
        }
    }

    private fun firstSync(folder: File, local: Map<String, LocalFile>, remote: RemoteRepo): String {
        if (remote.files.isEmpty()) {
            if (local.isEmpty()) return "✅ Repository and folder are empty"
            throw IllegalStateException("The GitHub repository is empty. For the first sync, put at least one commit in the repository or use the app with a non-empty repository.")
        }

        // First run: GitHub is the baseline. Populate the selected folder from the repository.
        // Existing files are overwritten so the first snapshot is guaranteed to match GitHub.
        val localPaths = local.keys.toSet()
        for (path in localPaths) {
            if (!remote.files.containsKey(path)) {
                safeFile(folder, path).delete()
            }
        }

        var pulled = 0
        for ((path, sha) in remote.files) {
            val destination = safeFile(folder, path)
            writeBlob(destination, getBlobBytes(remote.owner, remote.name, sha))
            pulled++
        }

        return "⬇️ Initial sync complete • $pulled file(s)"
    }

    private fun pullRemoteChanges(folder: File, remote: RemoteRepo, paths: Set<String>) {
        for (path in paths) {
            val sha = remote.files[path]
            val destination = safeFile(folder, path)
            if (sha == null) {
                if (destination.exists()) destination.delete()
                continue
            }
            writeBlob(destination, getBlobBytes(remote.owner, remote.name, sha))
        }
    }

    private fun pushLocalChanges(
        owner: String,
        name: String,
        branch: String,
        remote: RemoteRepo,
        changed: Map<String, String?>,
        folder: File
    ): RemoteRepo {
        val treeEntries = JSONArray()
        for ((path, sha) in changed) {
            val item = JSONObject()
                .put("path", path)
                .put("mode", "100644")
                .put("type", "blob")
            if (sha == null) {
                item.put("sha", JSONObject.NULL)
            } else {
                val localFile = safeFile(folder, path)
                val bytes = readBytes(localFile)
                val blobSha = createBlob(owner, name, bytes)
                item.put("sha", blobSha)
            }
            treeEntries.put(item)
        }

        val newTree = createTree(owner, name, remote.treeSha, treeEntries)
        val newCommit = createCommit(owner, name, newTree, remote.headSha)
        updateRef(owner, name, branch, newCommit)

        return fetchRemote(owner, name, branch)
    }

    private fun getDefaultBranch(owner: String, name: String): JSONObject {
        return requestJson("GET", "https://api.github.com/repos/$owner/$name")
    }

    private fun fetchRemote(owner: String, name: String, branch: String): RemoteRepo {
        val ref = requestJson(
            "GET",
            "https://api.github.com/repos/$owner/$name/git/ref/heads/${url(branch)}"
        )
        val headSha = ref.getJSONObject("object").getString("sha")
        val commit = requestJson(
            "GET",
            "https://api.github.com/repos/$owner/$name/git/commits/$headSha"
        )
        val treeSha = commit.getJSONObject("tree").getString("sha")
        val tree = requestJson(
            "GET",
            "https://api.github.com/repos/$owner/$name/git/trees/$treeSha?recursive=1"
        )
        val files = mutableMapOf<String, String>()
        val items = tree.getJSONArray("tree")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("type") == "blob") {
                files[item.getString("path")] = item.getString("sha")
            }
        }
        return RemoteRepo(owner, name, branch, headSha, treeSha, files)
    }

    private fun getBlobBytes(owner: String, name: String, sha: String): ByteArray {
        val blob = requestJson(
            "GET",
            "https://api.github.com/repos/$owner/$name/git/blobs/$sha"
        )
        if (blob.optString("encoding") != "base64") {
            throw IllegalStateException("Unsupported GitHub blob encoding for $sha")
        }
        return Base64.decode(blob.getString("content").replace("\n", ""), Base64.DEFAULT)
    }

    private fun createBlob(owner: String, name: String, bytes: ByteArray): String {
        val body = JSONObject()
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("encoding", "base64")
        val result = requestJson(
            "POST",
            "https://api.github.com/repos/$owner/$name/git/blobs",
            body
        )
        return result.getString("sha")
    }

    private fun createTree(owner: String, name: String, baseTree: String, entries: JSONArray): String {
        val body = JSONObject()
            .put("base_tree", baseTree)
            .put("tree", entries)
        return requestJson(
            "POST",
            "https://api.github.com/repos/$owner/$name/git/trees",
            body
        ).getString("sha")
    }

    private fun createCommit(owner: String, name: String, tree: String, parent: String): String {
        val body = JSONObject()
            .put("message", "GitSync auto-sync")
            .put("tree", tree)
            .put("parents", JSONArray().put(parent))
        return requestJson(
            "POST",
            "https://api.github.com/repos/$owner/$name/git/commits",
            body
        ).getString("sha")
    }

    private fun updateRef(owner: String, name: String, branch: String, commitSha: String) {
        val body = JSONObject().put("sha", commitSha).put("force", false)
        requestJson(
            "PATCH",
            "https://api.github.com/repos/$owner/$name/git/refs/heads/${url(branch)}",
            body
        )
    }

    private fun scanLocal(folder: File): Map<String, LocalFile> {
        val result = linkedMapOf<String, LocalFile>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.name == ".gitsync") continue
                if (child.isDirectory) {
                    walk(child)
                } else if (child.isFile) {
                    val relative = child.relativeTo(folder).invariantSeparatorsPath
                    result[relative] = LocalFile(child, gitBlobSha(child))
                }
            }
        }
        walk(folder)
        return result
    }

    private fun gitBlobSha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val header = "blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8)
        digest.update(header)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readBytes(file: File): ByteArray {
        if (!file.exists()) throw IllegalStateException("File disappeared: ${file.absolutePath}")
        return file.inputStream().use { it.readBytes() }
    }

    private fun writeBlob(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.outputStream().use { it.write(bytes) }
    }

    private fun safeFile(root: File, relative: String): File {
        val normalized = relative.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.split('/').any { it == ".." }) {
            throw IllegalArgumentException("Unsafe repository path: $relative")
        }
        return File(root, normalized)
    }

    private fun parseRepo(raw: String): Pair<String, String> {
        val cleaned = raw.trim()
            .removeSuffix("/")
            .removeSuffix(".git")
        val marker = "github.com/"
        val index = cleaned.indexOf(marker)
        if (index < 0) throw IllegalArgumentException("Use a GitHub URL like https://github.com/owner/repo")
        val path = cleaned.substring(index + marker.length).trim('/')
        val parts = path.split('/')
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw IllegalArgumentException("Invalid GitHub repository URL")
        }
        return parts[0] to parts[1]
    }

    private fun url(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun requestJson(method: String, url: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val message = try {
                    JSONObject(text).optString("message", text)
                } catch (_: Exception) {
                    text
                }
                throw IllegalStateException("GitHub HTTP $code: $message")
            }

            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun remoteSnapshotJson(files: Map<String, String>): String {
        val json = JSONObject()
        for ((path, sha) in files) json.put(path, sha)
        return json.toString()
    }

    private fun localSnapshotJson(files: Map<String, LocalFile>): String {
        val json = JSONObject()
        for ((path, file) in files) json.put(path, file.gitSha)
        return json.toString()
    }

    private fun parseSnapshot(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        val json = try { JSONObject(raw) } catch (_: Exception) { return emptyMap() }
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = json.optString(key)
        }
        return result
    }

}
