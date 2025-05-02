package com.example.visiontest.config

/**
 * Application configuration.
 *
 * This class centralizes all application configurations
 * to facilitate maintenance and testing.
 */
data class AppConfig(
    // Server configuration
    val serverName: String = "vision-test",
    val serverVersion: String = "1.0.0",

    // ADB Configuration
    val adbTimeoutMillis: Long = 5000L,
    val deviceCacheValidityPeriod: Long = 1000L,

    // Tool configuration
    val toolTimeoutMillis: Long = 10000L,

    // Enable/disable features
    val enableLogging: Boolean = true
) {
    companion object {
        /**
         * Creates a configuration with default values.
         */
        fun createDefault(): AppConfig = AppConfig()

        /**
         * Loads configuration from a properties file (to be implemented if needed).
         */
        fun loadFromProperties(path: String): AppConfig {
            // Future implementation: load from file
            return createDefault()
        }
    }
}
