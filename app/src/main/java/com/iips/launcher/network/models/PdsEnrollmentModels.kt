package com.iips.launcher.network.models

import com.google.gson.annotations.SerializedName

data class PdsEnrollmentChallengeRequest(
    @SerializedName("enrollment_token") val enrollmentToken: String,
    @SerializedName("tenant_id") val tenantId: String,
    @SerializedName("device_public_key_spki_b64") val devicePublicKeySpkiB64: String? = null,
    @SerializedName("hardware") val hardware: PdsHardwareWire
)

data class PdsHardwareWire(
    @SerializedName("manufacturer") val manufacturer: String,
    @SerializedName("model") val model: String,
    @SerializedName("hardware_id") val hardwareId: String,
    @SerializedName("serial_number") val serialNumber: String?,
    @SerializedName("android_version") val androidVersion: String,
    @SerializedName("sdk_int") val sdkInt: Long,
    @SerializedName("build_fingerprint") val buildFingerprint: String,
    @SerializedName("pds_version") val pdsVersion: String,
    @SerializedName("vendor") val vendor: String? = null,
    @SerializedName("capability_profile") val capabilityProfile: List<String> = emptyList()
)

data class PdsEnrollmentChallengeResponse(
    @SerializedName("challenge_id") val challengeId: String,
    @SerializedName("challenge_nonce") val challengeNonce: String,
    @SerializedName("attestation_challenge_b64") val attestationChallengeB64: String,
    @SerializedName("tenant_id") val tenantId: String,
    @SerializedName("public_key_fingerprint_sha256") val publicKeyFingerprintSha256: String? = null,
    @SerializedName("hardware_fingerprint_sha256") val hardwareFingerprintSha256: String,
    @SerializedName("issued_at") val issuedAt: String,
    @SerializedName("expires_at") val expiresAt: String
)

data class PdsEnrollmentVerifyRequest(
    @SerializedName("challenge_id") val challengeId: String,
    @SerializedName("enrollment_token") val enrollmentToken: String,
    @SerializedName("tenant_id") val tenantId: String,
    @SerializedName("device_public_key_spki_b64") val devicePublicKeySpkiB64: String,
    @SerializedName("hardware") val hardware: PdsHardwareWire,
    @SerializedName("challenge_signature_b64url") val challengeSignatureB64Url: String,
    @SerializedName("attestation") val attestation: Map<String, Any?>? = null,
    @SerializedName("recovery_nonce") val recoveryNonce: String? = null,
    @SerializedName("recovery_signature_b64url") val recoverySignatureB64Url: String? = null,
    @SerializedName("claimed_device_id") val claimedDeviceId: String? = null
)

data class PdsDeviceIdentityStatusResponse(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("tenant_id") val tenantId: String? = null,
    @SerializedName("pds_lifecycle_state") val lifecycleState: String? = null,
    @SerializedName("error_code") val errorCode: String? = null
)

data class PdsEnrollmentVerifyResponse(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_identity_ref") val deviceIdentityRef: String,
    @SerializedName("tenant_id") val tenantId: String,
    @SerializedName("device_key_id") val deviceKeyId: String,
    @SerializedName("key_version") val keyVersion: Long,
    @SerializedName("public_key_fingerprint_sha256") val publicKeyFingerprintSha256: String,
    @SerializedName("lifecycle_state") val lifecycleState: String,
    @SerializedName("attestation_trust_tier") val attestationTrustTier: String,
    @SerializedName("allowed_capabilities") val allowedCapabilities: List<String> = emptyList(),
    @SerializedName("allowed_commands") val allowedCommands: List<String> = emptyList(),
    @SerializedName("enrolled_at") val enrolledAt: String
)
