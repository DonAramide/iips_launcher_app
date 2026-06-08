package com.iips.launcher.guard.policy.recovery

enum class RecoveryType {
    GEOFENCE_REENTRY,
    NFC_READER,
    BLE_BEACON,
    MANAGER_APPROVAL
}

interface RecoveryStrategy {
    val type: RecoveryType
    fun authenticate(payload: String): Boolean
}
