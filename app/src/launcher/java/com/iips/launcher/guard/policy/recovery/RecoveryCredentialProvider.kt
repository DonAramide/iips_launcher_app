package com.iips.launcher.guard.policy.recovery

interface RecoveryCredentialProvider {
    val recoveryType: RecoveryType
    fun provideCredentials(): List<String>
}
