package com.iips.launcher.config

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Retrofit service for fetching remote configuration.
 */
interface ConfigService {
    
    /**
     * Fetch configuration from a dynamic URL.
     * This allows the user to specify any hosted JSON server.
     */
    @GET
    suspend fun fetchConfig(@Url url: String): Response<ConfigResponse>
}
