package com.iips.launcher.core.di

import android.content.Context
import com.iips.launcher.BuildConfig
import com.iips.launcher.network.ConfigService
import com.iips.launcher.network.MdmErrorInterceptor
import com.iips.launcher.storage.SecurePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(MdmErrorInterceptor())
            .addInterceptor(DynamicBaseUrlInterceptor(context))
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
    }

    @Provides
    @Singleton
    fun provideConfigService(retrofit: Retrofit): ConfigService {
        return retrofit.create(ConfigService::class.java)
    }
}

/**
 * Interceptor that dynamically rewrites the Retrofit base URL using the active configuration.
 *
 * Preference Order:
 * 1. SecurePreferences.getProvisioningBackendUrl(context) — Set during standard QR provisioning.
 * 2. SecurePreferences.getBackendUrl(context) — Backward compatibility fallback key.
 * 3. SecurePreferences.getConfigUrl(context) — Stored permanent config URL (falls back to BuildConfig.BASE_URL).
 *
 * Retrofit is instantiated with a stable placeholder (BuildConfig.BASE_URL); this interceptor is responsible for
 * rewriting the scheme, host, port, and path prefix for all requests bound to that host.
 */
class DynamicBaseUrlInterceptor(private val context: Context) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        var request = chain.request()
        val originalUrl = request.url

        val requestBuilder = request.newBuilder()
            .addHeader("ngrok-skip-browser-warning", "1")

        // Skip Authorization on paths ending in device/register
        if (originalUrl.encodedPath.endsWith("device/register")) {
            requestBuilder.removeHeader("Authorization")
        }
        request = requestBuilder.build()

        val parsedBase = BuildConfig.BASE_URL.toHttpUrlOrNull()
        
        if (parsedBase != null && originalUrl.host == parsedBase.host) {
            val customUrl = SecurePreferences.getProvisioningBackendUrl(context)
                ?: SecurePreferences.getBackendUrl(context)
                ?: SecurePreferences.getConfigUrl(context)

            if (!customUrl.isNullOrBlank() && customUrl != BuildConfig.BASE_URL) {
                try {
                    val normalizedUrl = com.iips.launcher.policy.DeviceAdminReceiver.normalizeBackendUrl(customUrl)
                    val parsedCustom = normalizedUrl.toHttpUrlOrNull()
                    if (parsedCustom != null) {
                        val baseSegmentCount = parsedBase.pathSize
                        val requestSegments = originalUrl.pathSegments
                        
                        val newUrlBuilder = parsedCustom.newBuilder()
                        if (requestSegments.size > baseSegmentCount) {
                            val relativeSegments = requestSegments.subList(baseSegmentCount, requestSegments.size)
                            for (segment in relativeSegments) {
                                newUrlBuilder.addPathSegment(segment)
                            }
                        }
                        val newHttpUrl = newUrlBuilder.build()
                        request = request.newBuilder().url(newHttpUrl).build()
                    }
                } catch (e: java.lang.Exception) {
                    android.util.Log.e("BaseUrlInterceptor", "Error rewriting base URL", e)
                }
            }
        }
        return chain.proceed(request)
    }
}

