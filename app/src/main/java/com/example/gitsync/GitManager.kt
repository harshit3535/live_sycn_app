package com.example.gitsync

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

class GitManager(private val repoDir: File, private val token: String) {

    private fun getRepo(): Git? {
        return if (repoDir.exists() && File(repoDir, ".git").exists()) Git.open(repoDir) else null
    }

    fun cloneRepo(url: String): Boolean {
        return try {
            Git.cloneRepository()
                .setURI(url)
                .setDirectory(repoDir)
                .setCredentialsProvider(UsernamePasswordCredentialsProvider(token, ""))
                .call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun pull(): Boolean {
        return try {
            getRepo()
                ?.pull()
                ?.setCredentialsProvider(UsernamePasswordCredentialsProvider(token, ""))
                ?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun push(): Boolean {
        return try {
            val git = getRepo() ?: return false
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Auto-sync from Phone").call()
            git.push()
                .setCredentialsProvider(UsernamePasswordCredentialsProvider(token, ""))
                .call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
