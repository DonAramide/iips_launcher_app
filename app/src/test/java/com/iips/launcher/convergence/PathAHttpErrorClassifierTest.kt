package com.iips.launcher.convergence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathAHttpErrorClassifierTest {
    @Test
    fun permanentAuthAndProof() {
        assertFalse(PathAHttpErrorClassifier.classify(401).retryable)
        assertEquals("unauthorized", PathAHttpErrorClassifier.classify(401).code)
        assertFalse(PathAHttpErrorClassifier.classify(403, "proof rejected").retryable)
        assertEquals("forbidden_or_proof", PathAHttpErrorClassifier.classify(403).code)
    }

    @Test
    fun retryableTransport() {
        assertTrue(PathAHttpErrorClassifier.classify(500).retryable)
        assertTrue(PathAHttpErrorClassifier.classify(408).retryable)
        assertTrue(PathAHttpErrorClassifier.classify(0).retryable)
        assertEquals("network_failure", PathAHttpErrorClassifier.classify(0).code)
    }

    @Test
    fun notFoundAndReplay() {
        assertEquals("command_not_found", PathAHttpErrorClassifier.classify(404).code)
        assertEquals("ack_nonce_replay", PathAHttpErrorClassifier.classify(409, "nonce replay").code)
        assertFalse(PathAHttpErrorClassifier.classify(404).retryable)
    }

    @Test
    fun skewIsNotTransientRetry() {
        val c = PathAHttpErrorClassifier.classify(403, "ack_ts_out_of_skew")
        assertEquals("ack_ts_out_of_skew", c.code)
        assertFalse(c.retryable)
    }
}
