package com.iips.launcher.network.models

import com.google.gson.annotations.SerializedName

/**
 * Standard Quasar JSON envelope used by Nest interceptors/filters.
 * PDS payloads live under [data].
 */
data class QuasarApiEnvelope<T>(
    @SerializedName("responseCode") val responseCode: String? = null,
    @SerializedName("responseMessage") val responseMessage: String? = null,
    @SerializedName("data") val data: T? = null
)
