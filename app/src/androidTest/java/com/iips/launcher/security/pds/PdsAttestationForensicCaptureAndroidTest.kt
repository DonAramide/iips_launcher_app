package com.iips.launcher.security.pds

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Instrumentation smoke: Android X.509 metadata APIs used by forensic capture.
 * Synthetic cert only — not MP45P hardware attestation evidence.
 */
@RunWith(AndroidJUnit4::class)
class PdsAttestationForensicCaptureAndroidTest {

    @Test
    fun androidX509ValidityMatchesInspector() {
        val cert = selfSigned(notBefore = Date(0L), notAfter = Date(-1000L))
        val record = PdsAttestationForensicCapture.inspectCertificates(
            listOf(cert),
            nowMillis = System.currentTimeMillis(),
            manufacturer = "Test",
            model = "Unit",
            androidApi = android.os.Build.VERSION.SDK_INT,
        ).single()
        assertEquals("1970-01-01T00:00:00.000Z", record.notBeforeUtc)
        assertEquals("1969-12-31T23:59:59.000Z", record.notAfterUtc)
        assertTrue(record.validityInverted)
        assertTrue(record.expired)
        assertFalse(record.keyDescriptionPresent)
        assertEquals(
            PdsAttestationForensicCapture.sha256Hex(cert.encoded),
            record.derSha256,
        )
    }

    private fun selfSigned(notBefore: Date, notAfter: Date): X509Certificate {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=p6543a-android")
        val spki = SubjectPublicKeyInfo.getInstance(kp.public.encoded)
        val builder = X509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            notBefore,
            notAfter,
            name,
            spki,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
