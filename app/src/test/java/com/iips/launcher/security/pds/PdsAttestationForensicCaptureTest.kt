package com.iips.launcher.security.pds

import com.iips.launcher.BuildConfig
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

class PdsAttestationForensicCaptureTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir(prefix = "p6543a-forensic-")
        PdsAttestationForensicCapture.enableOverrideForTests = true
    }

    @After
    fun tearDown() {
        PdsAttestationForensicCapture.enableOverrideForTests = null
        tempDir.deleteRecursively()
    }

    @Test
    fun validCertificateWindow() {
        val now = dateUtc(2026, Calendar.NOVEMBER, 9, 1, 0, 0)
        val cert = selfSigned(
            notBefore = dateUtc(2025, Calendar.JANUARY, 1, 0, 0, 0),
            notAfter = dateUtc(2027, Calendar.JANUARY, 1, 0, 0, 0),
        )
        val record = PdsAttestationForensicCapture.inspectCertificates(
            listOf(cert),
            nowMillis = now.time,
        ).single()
        assertFalse(record.expired)
        assertFalse(record.notYetValid)
        assertFalse(record.validityInverted)
        assertEquals(64, record.derSha256.length)
    }

    @Test
    fun expiredCertificate() {
        val now = dateUtc(2026, Calendar.NOVEMBER, 9, 1, 0, 0)
        val cert = selfSigned(
            notBefore = dateUtc(2020, Calendar.JANUARY, 1, 0, 0, 0),
            notAfter = dateUtc(2021, Calendar.JANUARY, 1, 0, 0, 0),
        )
        val record = PdsAttestationForensicCapture.inspectCertificates(
            listOf(cert),
            nowMillis = now.time,
        ).single()
        assertTrue(record.expired)
        assertFalse(record.notYetValid)
        assertFalse(record.validityInverted)
    }

    @Test
    fun notYetValidCertificate() {
        val now = dateUtc(2026, Calendar.NOVEMBER, 9, 1, 0, 0)
        val cert = selfSigned(
            notBefore = dateUtc(2027, Calendar.JANUARY, 1, 0, 0, 0),
            notAfter = dateUtc(2028, Calendar.JANUARY, 1, 0, 0, 0),
        )
        val record = PdsAttestationForensicCapture.inspectCertificates(
            listOf(cert),
            nowMillis = now.time,
        ).single()
        assertTrue(record.notYetValid)
        assertFalse(record.expired)
    }

    @Test
    fun invertedCertificateValidity() {
        val now = dateUtc(2026, Calendar.NOVEMBER, 9, 0, 45, 59)
        val cert = selfSigned(
            notBefore = Date(0L),
            notAfter = Date(-1000L),
        )
        val record = PdsAttestationForensicCapture.inspectCertificates(
            listOf(cert),
            nowMillis = now.time,
            manufacturer = "Topwise",
            model = "MP45P",
            androidApi = 27,
        ).single()
        assertTrue(record.validityInverted)
        assertTrue(record.expired)
        assertFalse(record.notYetValid)
        assertEquals("1970-01-01T00:00:00.000Z", record.notBeforeUtc)
        assertEquals("1969-12-31T23:59:59.000Z", record.notAfterUtc)
        assertEquals("Topwise", record.manufacturer)
        assertEquals("MP45P", record.model)
        assertEquals(27, record.androidApi)
    }

    @Test
    fun multipleCertificatesIndependentMetadata() {
        val now = dateUtc(2026, Calendar.JUNE, 1, 0, 0, 0)
        val leaf = selfSigned(
            notBefore = dateUtc(2025, Calendar.JANUARY, 1, 0, 0, 0),
            notAfter = dateUtc(2025, Calendar.JUNE, 1, 0, 0, 0),
            cn = "leaf",
        )
        val intermediate = selfSigned(
            notBefore = dateUtc(2024, Calendar.JANUARY, 1, 0, 0, 0),
            notAfter = dateUtc(2030, Calendar.JANUARY, 1, 0, 0, 0),
            cn = "intermediate",
        )
        val records = PdsAttestationForensicCapture.inspectCertificates(
            listOf(leaf, intermediate),
            nowMillis = now.time,
        )
        assertEquals(2, records.size)
        assertEquals(0, records[0].certificateIndex)
        assertEquals(1, records[1].certificateIndex)
        assertEquals(2, records[0].certificateChainLength)
        assertEquals(2, records[1].certificateChainLength)
        assertTrue(records[0].expired)
        assertFalse(records[1].expired)
        assertNotEquals(records[0].derSha256, records[1].derSha256)
    }

    @Test
    fun sha256FingerprintMatchesEncodedDer() {
        val cert = selfSigned(
            notBefore = dateUtc(2024, Calendar.JANUARY, 1, 0, 0, 0),
            notAfter = dateUtc(2028, Calendar.JANUARY, 1, 0, 0, 0),
        )
        val expected = PdsAttestationForensicCapture.sha256Hex(cert.encoded)
        val record = PdsAttestationForensicCapture.inspectCertificates(
            listOf(cert),
            nowMillis = dateUtc(2026, Calendar.JANUARY, 1, 0, 0, 0).time,
        ).single()
        assertEquals(expected, record.derSha256)
    }

    @Test
    fun missingKeyDescriptionExtension() {
        val cert = selfSigned(
            notBefore = dateUtc(2024, Calendar.JANUARY, 1, 0, 0, 0),
            notAfter = dateUtc(2028, Calendar.JANUARY, 1, 0, 0, 0),
            withKeyDescription = false,
        )
        assertFalse(PdsAttestationForensicCapture.hasKeyDescriptionExtension(cert))
        val record = PdsAttestationForensicCapture.inspectCertificates(
            listOf(cert),
            nowMillis = dateUtc(2026, Calendar.JANUARY, 1, 0, 0, 0).time,
        ).single()
        assertFalse(record.keyDescriptionPresent)
    }

    @Test
    fun presentKeyDescriptionExtension() {
        val cert = selfSigned(
            notBefore = dateUtc(2024, Calendar.JANUARY, 1, 0, 0, 0),
            notAfter = dateUtc(2028, Calendar.JANUARY, 1, 0, 0, 0),
            withKeyDescription = true,
        )
        assertTrue(PdsAttestationForensicCapture.hasKeyDescriptionExtension(cert))
        val record = PdsAttestationForensicCapture.inspectCertificates(
            listOf(cert),
            nowMillis = dateUtc(2026, Calendar.JANUARY, 1, 0, 0, 0).time,
        ).single()
        assertTrue(record.keyDescriptionPresent)
    }

    @Test
    fun emptyCertificateChain() {
        val records = PdsAttestationForensicCapture.inspectCertificates(emptyList())
        assertTrue(records.isEmpty())
        val written = PdsAttestationForensicCapture.captureBeforeQuasarTransmit(
            outputDir = tempDir,
            chainB64 = emptyList(),
            device = sampleDevice(),
            keystore = sampleKeystore(chainLength = 0),
        )
        assertTrue(written)
        val latest = File(tempDir, PdsAttestationForensicCapture.LATEST_FILE).readText()
        assertTrue(latest.contains("certificate_chain_empty_or_undecodable"))
        assertTrue(latest.contains("\"certificates\": []") || latest.contains("\"certificates\":[]"))
        PdsAttestationForensicCapture.assertNoSecrets(latest)
    }

    @Test
    fun undecodableChainDoesNotPersistSecrets() {
        val written = PdsAttestationForensicCapture.captureBeforeQuasarTransmit(
            outputDir = tempDir,
            chainB64 = listOf("%%%not-base64%%%"),
            device = sampleDevice(),
            keystore = sampleKeystore(chainLength = 1),
        )
        assertTrue(written)
        val latest = File(tempDir, PdsAttestationForensicCapture.LATEST_FILE).readText()
        assertFalse(latest.contains("%%%"))
        PdsAttestationForensicCapture.assertNoSecrets(latest)
    }

    @Test
    fun debugCaptureDisabledProducesNoFile() {
        PdsAttestationForensicCapture.enableOverrideForTests = false
        assertFalse(PdsAttestationForensicCapture.isCaptureEnabled())
        val written = PdsAttestationForensicCapture.captureBeforeQuasarTransmit(
            outputDir = tempDir,
            chainB64 = listOf(encode(selfSigned(
                notBefore = dateUtc(2024, Calendar.JANUARY, 1, 0, 0, 0),
                notAfter = dateUtc(2028, Calendar.JANUARY, 1, 0, 0, 0),
            ))),
            device = sampleDevice(),
            keystore = sampleKeystore(chainLength = 1),
        )
        assertFalse(written)
        assertFalse(File(tempDir, PdsAttestationForensicCapture.LATEST_FILE).exists())
    }

    @Test
    fun releaseCaptureGateIsDisabledConstant() {
        // Release source set hard-codes isEnabled()=false. Debug unit tests validate
        // the disabled path via override; BuildConfig release field is also false.
        PdsAttestationForensicCapture.enableOverrideForTests = false
        assertFalse(PdsAttestationForensicCapture.isCaptureEnabled())
        // Debug variant of this unit-test run should declare the compile-time flag true,
        // while release BuildConfig declares false (see app/build.gradle).
        assertTrue(
            "debug unit tests expect forensic flag true in BuildConfig",
            BuildConfig.PDS_ATTESTATION_FORENSIC_CAPTURE,
        )
        assertTrue(BuildConfig.DEBUG)
    }

    @Test
    fun diagnosticWriteFailureDoesNotThrow() {
        val notADirectory = File(tempDir, "blocked.txt")
        notADirectory.writeText("x")
        // Parent path exists but mkdirs on a file path fails softly inside capture.
        val written = PdsAttestationForensicCapture.captureBeforeQuasarTransmit(
            outputDir = notADirectory,
            chainB64 = listOf(encode(selfSigned(
                notBefore = dateUtc(2024, Calendar.JANUARY, 1, 0, 0, 0),
                notAfter = dateUtc(2028, Calendar.JANUARY, 1, 0, 0, 0),
            ))),
            device = sampleDevice(),
            keystore = sampleKeystore(chainLength = 1),
        )
        assertFalse(written)
    }

    @Test
    fun persistedOutputContainsNoPemDerSpkiOrCredentials() {
        val cert = selfSigned(
            notBefore = Date(0L),
            notAfter = Date(-1000L),
            withKeyDescription = false,
        )
        val derB64 = encode(cert)
        assertTrue(derB64.length > 100)
        val written = PdsAttestationForensicCapture.captureBeforeQuasarTransmit(
            outputDir = tempDir,
            chainB64 = listOf(derB64),
            device = sampleDevice(),
            keystore = sampleKeystore(chainLength = 1),
        )
        assertTrue(written)
        val latest = File(tempDir, PdsAttestationForensicCapture.LATEST_FILE).readText()
        PdsAttestationForensicCapture.assertNoSecrets(latest)
        assertFalse(latest.contains(derB64))
        assertFalse(latest.contains("BEGIN CERTIFICATE"))
        assertTrue(latest.contains("der_sha256"))
        assertTrue(latest.contains("validity_inverted"))
        assertTrue(latest.contains("1970-01-01T00:00:00.000Z"))
        assertTrue(latest.contains("1969-12-31T23:59:59.000Z"))
    }

    @Test
    fun sampleSanitizedRecordShapeFromInvertedTestCert() {
        val cert = selfSigned(
            notBefore = Date(0L),
            notAfter = Date(-1000L),
        )
        val record = PdsAttestationForensicCapture.inspectCertificates(
            listOf(cert),
            nowMillis = dateUtc(2026, Calendar.NOVEMBER, 9, 0, 45, 59).time,
            manufacturer = "Topwise",
            model = "MP45P",
            androidApi = 27,
        ).single()
        assertEquals(PdsAttestationForensicCapture.EVENT, record.event)
        assertEquals(1, record.captureVersion)
        assertEquals(0, record.certificateIndex)
        assertEquals(1, record.certificateChainLength)
        assertEquals(64, record.derSha256.length)
        assertTrue(record.validityInverted)
        assertTrue(record.expired)
        assertFalse(record.keyDescriptionPresent)
    }

    private fun sampleDevice() = PdsAttestationForensicCapture.DeviceProfile(
        manufacturer = "Topwise",
        model = "MP45P",
        androidApi = 27,
        buildFingerprintRedacted = "alps/full_tb8765/<build_id>:user/test-keys",
        buildTags = "test-keys",
        buildType = "user",
        securityPatchLevel = "2019-08-05",
    )

    private fun sampleKeystore(chainLength: Int) = PdsAttestationForensicCapture.KeystoreEvidence(
        aliasPresent = true,
        chainRetrieved = chainLength > 0,
        chainLength = chainLength,
        isInsideSecureHardware = true,
        securityLevelName = "tee",
        keyAlgorithm = "EC",
        keySizeBits = 256,
        strongBoxApplicable = false,
        strongBoxBacked = null,
        attestationChallengeConfigured = true,
        inspectionSource = "keyinfo_api26_27",
    )

    private fun encode(cert: X509Certificate): String =
        Base64.getEncoder().encodeToString(cert.encoded)

    private fun dateUtc(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Date {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month, day, hour, minute, second)
        return cal.time
    }

    private fun selfSigned(
        notBefore: Date,
        notAfter: Date,
        cn: String = "p6543a-test",
        withKeyDescription: Boolean = false,
    ): X509Certificate {
        val keyPair = ecKeyPair()
        val name = X500Name("CN=$cn")
        val spki = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        val builder = X509v3CertificateBuilder(
            name,
            BigInteger.valueOf(System.nanoTime()),
            notBefore,
            notAfter,
            name,
            spki,
        )
        if (withKeyDescription) {
            builder.addExtension(
                ASN1ObjectIdentifier(PdsAttestationForensicCapture.KEY_DESCRIPTION_OID),
                false,
                DEROctetString(DERNull.INSTANCE.encoded),
            )
        }
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun ecKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        return kpg.generateKeyPair()
    }
}
