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

    private val prefs =
        context.getSharedPreferences(
            "gitsync_settings",
            Context.MODE_PRIVATE
        )

    companion object {

        private const val KEY_ALIAS = "GitSyncSecretKey"

        private const val KEY_REPO = "repo_url"
        private const val KEY_FOLDER = "folder_path"
        private const val KEY_TOKEN = "token_encrypted"

        private const val TRANSFORMATION =
            "AES/GCM/NoPadding"
    }

    init {
        getOrCreateSecretKey()
    }

    fun saveSettings(
        repoUrl: String,
        token: String,
        folderPath: String
    ) {
        prefs.edit()
            .putString(KEY_REPO, repoUrl)
            .putString(KEY_FOLDER, folderPath)
            .putString(KEY_TOKEN, encrypt(token))
            .apply()
    }

    fun getRepoUrl(): String {
        return prefs.getString(KEY_REPO, "") ?: ""
    }

    fun getFolderPath(): String {
        return prefs.getString(KEY_FOLDER, "") ?: ""
    }

    fun getToken(): String {
        val encrypted = prefs.getString(KEY_TOKEN, "") ?: ""

        if (encrypted.isBlank()) {
            return ""
        }

        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun hasSettings(): Boolean {
        return getRepoUrl().isNotBlank() &&
                getToken().isNotBlank() &&
                getFolderPath().isNotBlank()
    }

    private fun getOrCreateSecretKey(): SecretKey {

        val keyStore =
            java.security.KeyStore.getInstance(
                "AndroidKeyStore"
            ).apply {
                load(null)
            }

        if (keyStore.containsAlias(KEY_ALIAS)) {

            return (keyStore.getEntry(
                KEY_ALIAS,
                null
            ) as java.security.KeyStore.SecretKeyEntry).secretKey
        }

        val keyGenerator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

        val keySpec =
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                        KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .build()

        keyGenerator.init(keySpec)

        return keyGenerator.generateKey()
    }

    private fun encrypt(value: String): String {

        val cipher =
            Cipher.getInstance(TRANSFORMATION)

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateSecretKey()
        )

        val encrypted =
            cipher.doFinal(
                value.toByteArray(
                    StandardCharsets.UTF_8
                )
            )

        val iv = cipher.iv

        val combined =
            ByteArray(
                iv.size + encrypted.size
            )

        System.arraycopy(
            iv,
            0,
            combined,
            0,
            iv.size
        )

        System.arraycopy(
            encrypted,
            0,
            combined,
            iv.size,
            encrypted.size
        )

        return Base64.encodeToString(
            combined,
            Base64.NO_WRAP
        )
    }

    private fun decrypt(value: String): String {

        val combined =
            Base64.decode(
                value,
                Base64.NO_WRAP
            )

        val ivLength = 12

        val iv =
            combined.copyOfRange(
                0,
                ivLength
            )

        val encrypted =
            combined.copyOfRange(
                ivLength,
                combined.size
            )

        val cipher =
            Cipher.getInstance(TRANSFORMATION)

        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(
                128,
                iv
            )
        )

        return String(
            cipher.doFinal(encrypted),
            StandardCharsets.UTF_8
        )
    }
}
