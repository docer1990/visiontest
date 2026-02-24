package com.example.visiontest.config

/**
 * Logging levels for the application.
 * Controls the verbosity and sensitivity of logged information.
 */
enum class LogLevel {
    /**
     * Production mode: minimal logging, no stack traces, no sensitive data.
     * Only critical errors are logged.
     */
    PRODUCTION,

    /**
     * Development mode: detailed logging with sanitized stack traces.
     * Useful for debugging without exposing sensitive information.
     */
    DEVELOPMENT,

    /**
     * Debug mode: full logging including complete stack traces.
     * Should only be used in secure development environments.
     */
    DEBUG
}

/**
 * Application configuration.
 *
 * This class centralizes all application configurations
 * to facilitate maintenance and testing.
 */
data class AppConfig(
    // Server configuration
    val serverName: String = "vision-test",
    val serverVersion: String = "0.1.0",

    // ADB Configuration
    val adbTimeoutMillis: Long = 5000L,
    val deviceCacheValidityPeriod: Long = 1000L,

    // Tool configuration
    val toolTimeoutMillis: Long = 10000L,

    // Logging configuration
    val logLevel: LogLevel = LogLevel.PRODUCTION
) {
    companion object {
        /**
         * Creates a configuration with default values.
         */
        fun createDefault(): AppConfig {
            // Read log level from environment variable, default to PRODUCTION
            val envLogLevel = System.getenv("VISION_TEST_LOG_LEVEL")?.uppercase()
            val logLevel = when (envLogLevel) {
                "DEBUG" -> LogLevel.DEBUG
                "DEVELOPMENT", "DEV" -> LogLevel.DEVELOPMENT
                else -> LogLevel.PRODUCTION
            }

            return AppConfig(logLevel = logLevel)
        }

        /**
         * Loads configuration from a properties file (to be implemented if needed).
         */
        fun loadFromProperties(path: String): AppConfig {
            // Future implementation: load from file
            return createDefault()
        }
    }
}
