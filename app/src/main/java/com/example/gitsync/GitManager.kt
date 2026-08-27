package com.example.gitsync

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

class GitManager(
    private val repoDir: File,
    private val token: String
) {

    private fun credentials(): UsernamePasswordCredentialsProvider {
        return UsernamePasswordCredentialsProvider("git", token)
    }

    private fun openRepo(): Git? {
        return try {
            if (repoDir.exists() && File(repoDir, ".git").exists()) {
                Git.open(repoDir)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun isRepository(): Boolean = File(repoDir, ".git").exists()

    fun cloneRepo(url: String): Boolean {
        return try {
            if (isRepository()) return true
            if (!repoDir.exists()) repoDir.mkdirs()

            val files = repoDir.listFiles()
            if (files != null && files.isNotEmpty()) return false

            Git.cloneRepository()
                .setURI(url)
                .setDirectory(repoDir)
                .setCredentialsProvider(credentials())
                .call()
                .close()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun pull(): Boolean {
        var git: Git? = null
        return try {
            git = openRepo() ?: return false
            git.pull()
                .setCredentialsProvider(credentials())
                .call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            git?.close()
        }
    }

    fun push(): Boolean {
        var git: Git? = null
        return try {
            git = openRepo() ?: return false

            git.add()
                .addFilepattern(".")
                .call()

            val status = git.status().call()
            if (status.isClean) return true

            git.commit()
                .setMessage("Auto-sync from Phone")
                .setAllowEmpty(false)
                .call()

            git.push()
                .setCredentialsProvider(credentials())
                .call()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            git?.close()
        }
    }
}
