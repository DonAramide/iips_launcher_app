package com.iips.launcher.security.pds

/**
 * Classifies Android Keystore hardware backing from KeyInfo.
 *
 * Does not infer hardware from successful key generation. Callers must
 * inspect the live alias (including after process restart).
 *
 * API 26–27: [isInsideSecureHardware] only.
 * API 28–30: StrongBox may be requested at generation time but KeyInfo
 *            cannot distinguish StrongBox from TEE after restart.
 * API 31+:   KeyInfo.securityLevel identifies StrongBox.
 */
object PdsHardwareBackingClassifier {
    const val SECURITY_LEVEL_UNKNOWN = -1
    const val SECURITY_LEVEL_SOFTWARE = 0
    const val SECURITY_LEVEL_TRUSTED_ENVIRONMENT = 1
    const val SECURITY_LEVEL_STRONGBOX = 2

    data class HardwareBackingInfo(
        val inspectedAlias: String,
        val isInsideSecureHardware: Boolean,
        val isStrongBox: Boolean,
        val securityLevelName: String,
        val apiLevel: Int,
        val inspectionSource: String
    )

    fun classify(
        alias: String,
        apiLevel: Int,
        isInsideSecureHardware: Boolean?,
        securityLevelApi31: Int?,
        aliasPresent: Boolean
    ): HardwareBackingInfo {
        if (!aliasPresent) {
            return HardwareBackingInfo(
                inspectedAlias = alias,
                isInsideSecureHardware = false,
                isStrongBox = false,
                securityLevelName = "absent",
                apiLevel = apiLevel,
                inspectionSource = "alias_missing"
            )
        }
        if (isInsideSecureHardware == null) {
            return HardwareBackingInfo(
                inspectedAlias = alias,
                isInsideSecureHardware = false,
                isStrongBox = false,
                securityLevelName = "unknown",
                apiLevel = apiLevel,
                inspectionSource = "keyinfo_unavailable"
            )
        }
        if (apiLevel >= 31 && securityLevelApi31 != null) {
            val strongBox = securityLevelApi31 == SECURITY_LEVEL_STRONGBOX
            val name = when (securityLevelApi31) {
                SECURITY_LEVEL_STRONGBOX -> "strongbox"
                SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
                SECURITY_LEVEL_SOFTWARE -> "software"
                else -> if (isInsideSecureHardware) "tee" else "software"
            }
            return HardwareBackingInfo(
                inspectedAlias = alias,
                isInsideSecureHardware = isInsideSecureHardware || strongBox,
                isStrongBox = strongBox,
                securityLevelName = name,
                apiLevel = apiLevel,
                inspectionSource = "keyinfo_api31"
            )
        }
        val name = if (isInsideSecureHardware) "tee" else "software"
        return HardwareBackingInfo(
            inspectedAlias = alias,
            isInsideSecureHardware = isInsideSecureHardware,
            isStrongBox = false,
            securityLevelName = name,
            apiLevel = apiLevel,
            inspectionSource = if (apiLevel >= 28) "keyinfo_api28_30" else "keyinfo_api26_27"
        )
    }
}
