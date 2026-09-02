package com.example.gitsync

import java.io.File
import java.security.MessageDigest

/**
 * Computes the same SHA-1 hash Git uses for blobs: sha1("blob " + size + "\0" + content).
 * This lets us compare a local file against GitHub's tree entry SHA without
 * downloading the file's content first.
 */
object GitBlobHasher {

    fun hashFile(file: File): String {
        val bytes = file.readBytes()
        return hashBytes(bytes)
    }

    fun hashBytes(bytes: ByteArray): String {
        val header = "blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(header)
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
