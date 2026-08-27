package com.example.gitsync

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

class GitManager(
    private val repoDir: File,
    private val token: String
) {

    private fun credentials(): UsernamePasswordCredentialsProvider {
        return UsernamePasswordCredentialsProvider(token, "")
    }

    private fun getRepo(): Git? {
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

    fun isRepository(): Boolean {
        return File(repoDir, ".git").exists()
    }

    fun cloneRepo(url: String): Boolean {
        return try {
            if (isRepository()) {
                true
            } else {
                if (!repoDir.exists()) {
                    repoDir.mkdirs()
                }

                val files = repoDir.listFiles()
                if (files != null && files.isNotEmpty()) {
                    false
                } else {
                    Git.cloneRepository()
                        .setURI(url)
                        .setDirectory(repoDir)
                        .setCredentialsProvider(credentials())
                        .call()

                    true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun pull(): Boolean {
        return try {
            val git = getRepo() ?: return false

            git.pull()
                .setCredentialsProvider(credentials())
                .call()

            git.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun push(): Boolean {
        return try {
            val git = getRepo() ?: return false

            git.add()
                .addFilepattern(".")
                .call()

            val status = git.status().call()

            if (status.isClean) {
                git.close()
                return true
            }

            git.commit()
                .setMessage("Auto-sync from Phone")
                .call()

            git.push()
                .setCredentialsProvider(credentials())
                .call()

            git.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
