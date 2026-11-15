package com.iips.launcher.security.pds

import com.iips.launcher.BuildConfig

/**
 * Debug build-type gate for Phase 6.5.4.3A forensic capture.
 * Still requires [BuildConfig.PDS_ATTESTATION_FORENSIC_CAPTURE] so the flag can be
 * flipped off in a debug CI variant without a source change.
 */
internal object PdsAttestationForensicGate {
    fun isEnabled(): Boolean =
        BuildConfig.DEBUG && BuildConfig.PDS_ATTESTATION_FORENSIC_CAPTURE
}
