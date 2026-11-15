package com.iips.launcher.network

import okhttp3.logging.HttpLoggingInterceptor

/**
 * Central OkHttp logging policy for Dotroid.
 *
 * Stage 2.5D.2: BODY logging is forbidden in every build type. Enrollment
 * tokens, challenge signatures, nonces, recovery proofs and credentials must
 * never enter logcat via OkHttp.
 *
 * - Debug / integration: [HttpLoggingInterceptor.Level.NONE]
 * - Release: [HttpLoggingInterceptor.Level.BASIC] (method + URL only; no headers/bodies)
 */
object OkHttpLoggingPolicy {
    fun level(debugBuild: Boolean): HttpLoggingInterceptor.Level {
        return if (debugBuild) {
            HttpLoggingInterceptor.Level.NONE
        } else {
            HttpLoggingInterceptor.Level.BASIC
        }
    }

    fun forbidsBodyLogging(level: HttpLoggingInterceptor.Level): Boolean {
        return level != HttpLoggingInterceptor.Level.BODY &&
            level != HttpLoggingInterceptor.Level.HEADERS
    }
}
