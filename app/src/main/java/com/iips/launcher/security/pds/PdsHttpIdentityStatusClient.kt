package com.iips.launcher.security.pds

import com.iips.launcher.network.ConfigService
import java.io.IOException

/**
 * Authenticated identity-status client. Definitive absence requires the
 * explicit `device_not_registered` error code from Quasar 1D.2.
 */
class PdsHttpIdentityStatusClient(
    private val configService: ConfigService
) : PdsDeviceIdentityStatusClient {

    override suspend fun probeRegisteredIdentity(
        deviceId: String,
        tenantId: String,
        enrollmentToken: String
    ): PdsDeviceIdentityProbe {
        return try {
            val response = configService.pdsDeviceIdentityStatus(
                deviceId = deviceId,
                tenantId = tenantId,
                enrollmentToken = enrollmentToken
            )
            val errorCode = PdsEnrollmentHttpErrors.parseErrorCode(response.errorBody()?.string())
                ?: response.body()?.errorCode
            when {
                errorCode == PdsEnrollmentHttpErrors.DEVICE_NOT_REGISTERED ->
                    PdsDeviceIdentityProbe(PdsDeviceIdentityStatusKind.DEFINITIVELY_MISSING, errorCode)
                response.isSuccessful &&
                    response.body() != null &&
                    PdsEnrollmentIdentityPolicy.isQuasarDeviceUuid(response.body()!!.deviceId) &&
                    response.body()!!.deviceId == deviceId ->
                    PdsDeviceIdentityProbe(PdsDeviceIdentityStatusKind.CONFIRMED_PRESENT, errorCode)
                else ->
                    PdsDeviceIdentityProbe(PdsDeviceIdentityStatusKind.INDETERMINATE, errorCode)
            }
        } catch (_: IOException) {
            PdsDeviceIdentityProbe(PdsDeviceIdentityStatusKind.INDETERMINATE, "network_failure")
        } catch (_: Exception) {
            PdsDeviceIdentityProbe(PdsDeviceIdentityStatusKind.INDETERMINATE, "probe_failed")
        }
    }
}
