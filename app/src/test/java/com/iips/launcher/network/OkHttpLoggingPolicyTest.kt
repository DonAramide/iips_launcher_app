package com.iips.launcher.network

import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpLoggingPolicyTest {
    @Test
    fun debugIntegration_usesNone_neverBody() {
        val level = OkHttpLoggingPolicy.level(debugBuild = true)
        assertEquals(HttpLoggingInterceptor.Level.NONE, level)
        assertNotEquals(HttpLoggingInterceptor.Level.BODY, level)
        assertTrue(OkHttpLoggingPolicy.forbidsBodyLogging(level))
    }

    @Test
    fun release_usesBasic_neverBodyOrHeaders() {
        val level = OkHttpLoggingPolicy.level(debugBuild = false)
        assertEquals(HttpLoggingInterceptor.Level.BASIC, level)
        assertNotEquals(HttpLoggingInterceptor.Level.BODY, level)
        assertNotEquals(HttpLoggingInterceptor.Level.HEADERS, level)
        assertTrue(OkHttpLoggingPolicy.forbidsBodyLogging(level))
    }

    @Test
    fun forbidsBodyLogging_rejectsBodyAndHeaders() {
        assertFalse(OkHttpLoggingPolicy.forbidsBodyLogging(HttpLoggingInterceptor.Level.BODY))
        assertFalse(OkHttpLoggingPolicy.forbidsBodyLogging(HttpLoggingInterceptor.Level.HEADERS))
        assertTrue(OkHttpLoggingPolicy.forbidsBodyLogging(HttpLoggingInterceptor.Level.BASIC))
        assertTrue(OkHttpLoggingPolicy.forbidsBodyLogging(HttpLoggingInterceptor.Level.NONE))
    }
}
