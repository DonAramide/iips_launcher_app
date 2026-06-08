package com.iips.launcher.core

/**
 * Centralized branding configuration for the Dotroid platform.
 */
object BrandingConfig {
    const val PRODUCT_NAME = "Dotroid"
    const val INTERNAL_RUNTIME_ID = "com.iips.launcher"
    
    // Telemetry & Analytics labels
    const val TELEMETRY_PLATFORM_NAME = "Dotroid"
    
    // Legacy support flags if needed
    const val PREVIOUS_PRODUCT_NAME = "Dotoid"
}

/**
 * Provider for brand-related strings and assets throughout the application.
 */
class PlatformBrandProvider {
    fun getProductName(): String = BrandingConfig.PRODUCT_NAME
    fun getFullBrandingLabel(): String = "${BrandingConfig.PRODUCT_NAME} Enterprise"
}
