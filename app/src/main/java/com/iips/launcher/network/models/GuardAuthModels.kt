package com.iips.launcher.network.models

data class GuardLoginRequest(
    val email: String? = null,
    val phone: String? = null,
    val password: String
)

data class GuardLoginApiResponse(
    val responseCode: String?,
    val responseMessage: String?,
    val data: GuardLoginResponse?
)

data class GuardLoginResponse(
    val token: String,
    val refreshToken: String,
    val manager: ManagerProfileResponse
)

data class ManagerProfileResponse(
    val id: String,
    val name: String,
    val email: String,
    val phone: String,
    val role: String
)

data class TokenRefreshRequest(
    val refreshToken: String
)

data class ManagerDeviceRegistrationRequest(
    val managerId: String,
    val deviceId: String,
    val deviceName: String,
    val osVersion: String,
    val fcmToken: String
)

data class GuardSignupRequest(
    val name: String,
    val email: String,
    val phone: String,
    val password: String
)

data class GuardForgotPasswordRequest(
    val email: String
)

data class GuardCommonApiResponse(
    val responseCode: String?,
    val responseMessage: String?
)

data class GuardGoogleLoginRequest(
    val idToken: String,
    val email: String,
    val name: String? = null
)
