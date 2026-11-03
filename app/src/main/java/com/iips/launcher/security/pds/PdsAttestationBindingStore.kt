package com.iips.launcher.security.pds

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable SHA-256 hex of the server attestation challenge used at Keystore generation.
 */
class PdsAttestationBindingStore(
    private val storageFile: File
) {
    fun readHash(): String? {
        if (!storageFile.exists()) return null
        return try {
            storageFile.readText(StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun writeHash(hashHex: String) {
        require(hashHex.isNotBlank()) { "attestation binding hash is required" }
        val parent = storageFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(storageFile.parentFile, "${storageFile.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(hashHex.trim().lowercase().toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        Files.move(
            tmp.toPath(),
            storageFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }
}
