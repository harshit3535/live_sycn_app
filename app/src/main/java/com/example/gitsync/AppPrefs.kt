package com.example.gitsync

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class AppPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("gitsync", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "GitSyncTokenKey"
        private const val REPO = "repo"
        private const val TOKEN = "token"
        private const val FOLDER = "folder"
        private const val BRANCH = "branch"
        private const val SNAPSHOT_LOCAL = "snapshot_local"
        private const val SNAPSHOT_REMOTE = "snapshot_remote"
        private const val SNAPSHOT_HEAD = "snapshot_head"
        private const val STATUS = "status"
        private const val LAST_SYNC = "last_sync"
    }

    init {
        secretKey()
    }

    fun saveSettings(repo: String, token: String, folder: String) {
        prefs.edit()
            .putString(REPO, repo)
            .putString(TOKEN, encrypt(token))
            .putString(FOLDER, folder)
            .remove(BRANCH)
            .remove(SNAPSHOT_LOCAL)
            .remove(SNAPSHOT_REMOTE)
            .remove(SNAPSHOT_HEAD)
            .apply()
    }

    fun repo(): String = prefs.getString(REPO, "") ?: ""
    fun folder(): String = prefs.getString(FOLDER, "") ?: ""
    fun token(): String = prefs.getString(TOKEN, "")?.let { if (it.isBlank()) "" else decrypt(it) } ?: ""

    fun branch(): String = prefs.getString(BRANCH, "") ?: ""
    fun setBranch(value: String) = prefs.edit().putString(BRANCH, value).apply()

    fun localSnapshot(): String = prefs.getString(SNAPSHOT_LOCAL, "") ?: ""
    fun remoteSnapshot(): String = prefs.getString(SNAPSHOT_REMOTE, "") ?: ""
    fun snapshotHead(): String = prefs.getString(SNAPSHOT_HEAD, "") ?: ""

    fun saveSnapshot(local: String, remote: String, head: String) {
        prefs.edit()
            .putString(SNAPSHOT_LOCAL, local)
            .putString(SNAPSHOT_REMOTE, remote)
            .putString(SNAPSHOT_HEAD, head)
            .putString(LAST_SYNC, System.currentTimeMillis().toString())
            .apply()
    }

    fun status(): String = prefs.getString(STATUS, "Status: Stopped") ?: "Status: Stopped"
    fun setStatus(value: String) = prefs.edit().putString(STATUS, value).putLong(LAST_SYNC, System.currentTimeMillis()).apply()

    fun hasSettings(): Boolean = repo().isNotBlank() && token().isNotBlank() && folder().isNotBlank()

    private fun secretKey(): SecretKey {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (keyStore.getEntry(KEY_ALIAS, null) as java.security.KeyStore.SecretKeyEntry).secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(cipher.iv.size + cipherText.size)
        System.arraycopy(cipher.iv, 0, combined, 0, cipher.iv.size)
        System.arraycopy(cipherText, 0, combined, cipher.iv.size, cipherText.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        return try {
            val combined = Base64.decode(value, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val cipherText = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
