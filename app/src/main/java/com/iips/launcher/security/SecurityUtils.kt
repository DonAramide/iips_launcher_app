package com.iips.launcher.security

import android.content.Context
import java.io.File

/**
 * Utility class for security checks like root detection.
 */
object SecurityUtils {

    /**
     * Checks if the device is likely rooted.
     */
    fun isDeviceRooted(): Boolean {
        return checkRootMethod1() || checkRootMethod2() || checkRootMethod3()
    }

    private fun checkRootMethod1(): Boolean {
        val buildTags = android.os.Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkRootMethod2(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        for (path in paths) {
            if (File(path).exists()) return true
        }
        return false
    }

    private fun checkRootMethod3(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val reader = process.inputStream.bufferedReader()
            reader.readLine() != null
        } catch (t: Throwable) {
            false
        } finally {
            process?.destroy()
        }
    }

    /**
     * Verifies an HMAC-SHA256 signature for the given data using constant-time comparison.
     */
    fun verifyHmacSignature(data: String, signature: String?, secret: String): Boolean {
        if (signature == null) return false
        return try {
            val hmacKey = javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256")
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(hmacKey)
            val computedHash = mac.doFinal(data.toByteArray())
            // Convert to base64 or hex? The user suggested Base64 for X-Signature: <base64_hmac>
            val expectedBase64 = android.util.Base64.encodeToString(computedHash, android.util.Base64.NO_WRAP)
            
            // Fallback to hex if backend uses hex
            val hexString = computedHash.joinToString("") { "%02x".format(it) }
            
            secureCompare(expectedBase64, signature) || secureCompare(hexString, signature)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Advanced HMAC verification with Replay Protection (Timestamp & Nonce) and Key Derivation.
     */
    fun verifyHmac(
        context: Context,
        payload: String,
        signature: String?,
        secret: String,
        timestamp: String?,
        nonce: String?
    ): Boolean {
        if (signature == null || timestamp == null || nonce == null) {
            android.util.Log.e("SecurityUtils", "Missing signature headers")
            return false
        }
        
        // 1. Replay Protection: Timestamp Window (5 minutes)
        val tsLong = timestamp.toLongOrNull() ?: return false
        val now = System.currentTimeMillis()
        val ALLOWED_WINDOW = 5 * 60 * 1000 
        if (Math.abs(now - tsLong) > ALLOWED_WINDOW) {
            android.util.Log.e("SecurityUtils", "Replay protection: Timestamp outside allowed window")
            return false
        }
        
        // 2. Replay Protection: Nonce Cache
        if (com.iips.launcher.storage.SecurePreferences.isNonceReplayed(context, nonce)) {
            android.util.Log.e("SecurityUtils", "Replay protection: Nonce already used")
            return false
        }
        
        // 3. Key Derivation to prevent token leakage
        val serverSalt = "iips_mdm_salt_2026" // Fixed server salt for derivation
        val derivedKey = sha256(secret + serverSalt)
        
        // 4. HMAC Verification over payload + timestamp + nonce
        val dataToHash = payload + timestamp + nonce
        return verifyHmacSignature(dataToHash, signature, derivedKey)
    }

    /**
     * Secure constant-time string comparison to prevent timing attacks.
     */
    fun secureCompare(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }

    /**
     * Calculates SHA-256 hash of a string payload.
     */
    fun sha256(data: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Calculates the SHA-256 checksum of a file.
     */
    fun calculateFileSha256(file: File): String {
        return file.inputStream().use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    /**
     * Retrieves the hardware serial number of the device, with fallback options.
     */
    fun getSerialNumber(context: Context): String {
        try {
            val s = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    android.os.Build.getSerial()
                } catch (se: SecurityException) {
                    @Suppress("DEPRECATION")
                    android.os.Build.SERIAL
                }
            } else {
                @Suppress("DEPRECATION")
                android.os.Build.SERIAL
            }
            if (!s.isNullOrBlank() && !s.equals("unknown", ignoreCase = true)) {
                return s
            }
        } catch (e: Exception) {
            // Ignore
        }

        try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)
            val serial = get.invoke(c, "ro.serialno") as String
            if (!serial.isNullOrBlank() && !serial.equals("unknown", ignoreCase = true)) {
                return serial
            }
        } catch (e: Exception) {
            // Ignore
        }

        try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)
            val serial = get.invoke(c, "ril.serialnumber") as String
            if (!serial.isNullOrBlank() && !serial.equals("unknown", ignoreCase = true)) {
                return serial
            }
        } catch (e: Exception) {
            // Ignore
        }

        try {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            if (!androidId.isNullOrBlank() && !androidId.equals("unknown", ignoreCase = true)) {
                return androidId
            }
        } catch (e: Exception) {
            // Ignore
        }

        return "unknown_serial"
    }

    /**
     * Calculates the Dotoid fingerprint_hash (§4).
     * Format: SHA256Hex(manufacturer | model | (serial ?: "no_serial"))
     */
    fun calculateFingerprintHash(context: Context): String {
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL
        val serial = getSerialNumber(context)
        val raw = "$manufacturer|$model|$serial"
        return sha256(raw)
    }
}
