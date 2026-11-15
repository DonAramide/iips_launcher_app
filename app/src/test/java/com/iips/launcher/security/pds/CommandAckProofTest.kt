package com.iips.launcher.security.pds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Stage 2A.3 — Android result/note contract + cross-platform hash vectors.
 *
 * Documented execution-result contract:
 * - Quasar Path A ack DTO accepts only JSON objects / null / absent for `result`.
 * - Android handlers SHOULD return Map objects when possible.
 * - Scalars / lists are normalized via [CommandAckProof.resultObjectFrom] to
 *   `{"value": …}` BEFORE signing and BEFORE transmission — the signed object
 *   MUST equal the transmitted object.
 */
class CommandAckProofTest {
    private class SoftwareSigner : PdsDeviceKeySigner {
        private val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        override fun keyAlias(): String = "test_ack_key"
        override fun publicKeySpkiB64(): String =
            Base64.getEncoder().encodeToString(pair.public.encoded)

        override fun publicKeyFingerprintSha256(): String = "test"

        override fun signPayload(payloadBytes: ByteArray): String {
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(pair.private)
            signer.update(payloadBytes)
            return PdsCanonicalCommandEnvelope.toBase64Url(signer.sign())
        }

        override fun attestationChainB64(): List<String> = emptyList()
        override fun isStrongBoxBacked(): Boolean = false
        override fun isHardwareBacked(): Boolean = false
        override fun inspectHardwareBacking(): PdsHardwareBackingClassifier.HardwareBackingInfo =
            PdsHardwareBackingClassifier.classify(
                alias = keyAlias(),
                apiLevel = 28,
                isInsideSecureHardware = false,
                securityLevelApi31 = null,
                aliasPresent = true
            )
        override fun exportPrivateKeyBytes(): ByteArray =
            throw UnsupportedOperationException("non-exportable test key")
    }

    // Must match Quasar ACK_V2_VECTORS in canonical-command-ack.spec.ts
    private val ABSENT = "0b6d498b4ebc42c4eac30101fe491c2a1a7f5813c4187b52112806082be98a8d"
    private val NULL_MARK = "204f47664867af161a28de47a0baafe5aed9c840d4807080a8f28575274dfbe5"
    private val RESULT_EMPTY = "440e32158847d68e08a3311cdccc2a06e5d3f27f047d72a8923ec2be6f9cc450"
    private val RESULT_OK = "69ebc5285ca6c73fe11f984305ecf05d09c353ca8cbb028a924495cad65c0518"
    private val NOTE_DONE = "a3ab60c2da974d1c14e70afac00125f8cd31e7030eca403315c0364b41532022"
    private val NOTE_EMPTY = "9509a4f032c9f12f8d1521aacecde567ec3d376809f2ebce12dfe2d3494f8180"

    @Test
    fun normalizeMapsRejectedToFailed() {
        assertEquals("FAILED", CommandAckProof.normalizeStatus("REJECTED"))
        assertEquals("SUCCESS", CommandAckProof.normalizeStatus("success"))
        assertEquals("RECEIVED", CommandAckProof.normalizeStatus("RECEIVED"))
        assertEquals("EXECUTING", CommandAckProof.normalizeStatus("EXECUTING"))
    }

    @Test
    fun canonicalJsonSortsKeys() {
        assertEquals(
            "{\"a\":{\"c\":3,\"d\":2},\"b\":1}",
            CommandAckProof.canonicalJson(mapOf("b" to 1, "a" to mapOf("d" to 2, "c" to 3)))
        )
    }

    @Test
    fun crossPlatformHashVectors() {
        assertEquals(ABSENT, CommandAckProof.hashAckResultAbsent())
        assertEquals(NULL_MARK, CommandAckProof.hashAckResultNull())
        assertEquals(RESULT_EMPTY, CommandAckProof.hashAckResult(emptyMap()))
        assertEquals(RESULT_OK, CommandAckProof.hashAckResult(mapOf("code" to "OK", "n" to 1)))
        assertEquals(ABSENT, CommandAckProof.hashAckNoteAbsent())
        assertEquals(NULL_MARK, CommandAckProof.hashAckNoteNull())
        assertEquals(NOTE_DONE, CommandAckProof.hashAckNote("done"))
        assertEquals(NOTE_EMPTY, CommandAckProof.hashAckNote(""))
    }

    @Test
    fun createProducesV2TranscriptBinding() {
        val signer = SoftwareSigner()
        val proof = CommandAckProof.create(
            signer = signer,
            commandId = "cmd-1",
            deviceId = "dev-1",
            status = "SUCCESS",
            result = mapOf("code" to "OK", "n" to 1),
            note = "done",
            ackNonce = "nonce-abc",
            ackTs = 1_700_000_000L
        )
        assertEquals("SUCCESS", proof.status)
        assertTrue(proof.transcriptUtf8.startsWith("dotroid-cmd-ack-v2\n"))
        assertFalse(proof.transcriptUtf8.contains("dotroid-cmd-ack-v1"))
        assertTrue(proof.transcriptUtf8.contains("result_sha256=$RESULT_OK"))
        assertTrue(proof.transcriptUtf8.contains("note_sha256=$NOTE_DONE"))
        assertEquals(RESULT_OK, proof.resultSha256)
        assertEquals(NOTE_DONE, proof.noteSha256)
    }

    @Test
    fun resultObjectFromEmptyObject() {
        assertEquals(emptyMap<String, Any?>(), CommandAckProof.resultObjectFrom(emptyMap<String, Any?>()))
        assertEquals(RESULT_EMPTY, CommandAckProof.hashAckResult(CommandAckProof.resultObjectFrom(emptyMap<String, Any?>())))
    }

    @Test
    fun resultObjectFromPopulatedAndNested() {
        val nested = mapOf("outer" to mapOf("inner" to listOf(1, 2), "flag" to true))
        val obj = CommandAckProof.resultObjectFrom(nested)
        assertEquals(nested, obj)
        val json = CommandAckProof.canonicalJson(obj)
        assertEquals("{\"outer\":{\"flag\":true,\"inner\":[1,2]}}", json)
    }

    @Test
    fun resultObjectFromWrapsScalarsBooleansNumbersUnicode() {
        assertEquals(mapOf("value" to "SUCCESS"), CommandAckProof.resultObjectFrom("SUCCESS"))
        assertEquals(mapOf("value" to 42), CommandAckProof.resultObjectFrom(42))
        assertEquals(mapOf("value" to true), CommandAckProof.resultObjectFrom(true))
        assertEquals(mapOf("value" to "café — 你好"), CommandAckProof.resultObjectFrom("café — 你好"))
        assertEquals(mapOf("value" to null), CommandAckProof.resultObjectFrom(null))
    }

    @Test
    fun resultObjectFromWrapsArraysAsValueField() {
        val wrapped = CommandAckProof.resultObjectFrom(listOf("a", 1, false))
        assertEquals(mapOf("value" to listOf("a", 1, false)), wrapped)
        assertEquals("{\"value\":[\"a\",1,false]}", CommandAckProof.canonicalJson(wrapped))
    }

    @Test
    fun noteAbsentNullEmptyAndUnicodeHashDistinctly() {
        val absent = CommandAckProof.hashAckNoteAbsent()
        val nullMark = CommandAckProof.hashAckNoteNull()
        val empty = CommandAckProof.hashAckNote("")
        val unicode = CommandAckProof.hashAckNote("ok — 完了")
        assertEquals(ABSENT, absent)
        assertEquals(NULL_MARK, nullMark)
        assertEquals(NOTE_EMPTY, empty)
        assertTrue(setOf(absent, nullMark, empty, unicode).size == 4)
    }

    @Test
    fun createBindsAbsentAndNullResultNoteSemantics() {
        val signer = SoftwareSigner()
        val absent = CommandAckProof.create(
            signer = signer,
            commandId = "c",
            deviceId = "d",
            status = "SUCCESS",
            resultAbsent = true,
            noteAbsent = true,
            ackNonce = "n1",
            ackTs = 1L
        )
        assertEquals(ABSENT, absent.resultSha256)
        assertEquals(ABSENT, absent.noteSha256)

        val nulls = CommandAckProof.create(
            signer = signer,
            commandId = "c",
            deviceId = "d",
            status = "FAILED",
            resultNull = true,
            noteNull = true,
            ackNonce = "n2",
            ackTs = 2L
        )
        assertEquals(NULL_MARK, nulls.resultSha256)
        assertEquals(NULL_MARK, nulls.noteSha256)
    }

    @Test
    fun signedObjectEqualsTransmittedObjectContract() {
        // Engine must hash the same Map it places into the wire frame.
        val resultData: Any? = "SUCCESS"
        val resultObj = CommandAckProof.resultObjectFrom(resultData)
        val proof = CommandAckProof.create(
            signer = SoftwareSigner(),
            commandId = "cmd",
            deviceId = "dev",
            status = "SUCCESS",
            result = resultObj,
            note = "audit",
            ackNonce = "nonce",
            ackTs = 99L
        )
        assertEquals(CommandAckProof.hashAckResult(resultObj), proof.resultSha256)
        assertEquals(mapOf("value" to "SUCCESS"), resultObj)
    }

    @Test
    fun unsupportedCanonicalTypeFailsClosed() {
        class Weird
        assertThrows(IllegalArgumentException::class.java) {
            CommandAckProof.canonicalJson(Weird())
        }
    }
}
