package com.iips.launcher.config

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class DeviceNotActivatedException(message: String) : IOException(message)
class RateLimitExceededException(message: String, val retryAfter: String?) : IOException(message)
class NetworkFailureException(message: String, cause: Throwable) : IOException(message, cause)

/**
 * Intercepts HTTP responses and maps specific MDM errors to strongly typed Exceptions.
 */
class MdmErrorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response: Response
        
        try {
            response = chain.proceed(request)
        } catch (e: IOException) {
            throw NetworkFailureException("Network failure while calling ${request.url}", e)
        }

        when (response.code) {
            401 -> throw DeviceNotActivatedException("Device is not activated or token is invalid. (401)")
            429 -> {
                val retryAfter = response.header("Retry-After")
                throw RateLimitExceededException("Rate limit exceeded. (429)", retryAfter)
            }
        }

        return response
    }
}
