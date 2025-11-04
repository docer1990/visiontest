package com.example.visiontest.utils

import com.example.visiontest.*
import com.example.visiontest.android.*
import com.example.visiontest.config.AppConfig
import com.example.visiontest.config.LogLevel
import io.modelcontextprotocol.kotlin.sdk.*
import kotlinx.coroutines.delay
import org.slf4j.Logger
import java.util.concurrent.TimeoutException

/**
 * Centralized error handler for the application.
 *
 * Provides utility methods to handle and log errors consistently
 * throughout the application.
*/
object ErrorHandler {

    // Error messages
    const val PACKAGE_NAME_REQUIRED = "Error: packageName is required"
    const val BUNDLE_ID_REQUIRED = "Error: bundleId is required"
    const val DEVICE_NOT_FOUND = "No Android device available"

    // Error codes
    const val ERROR_DEVICE_NOT_FOUND = "ERR_NO_DEVICE"
    const val ERROR_COMMAND_FAILED = "ERR_CMD_FAILED"
    const val ERROR_PACKAGE_NOT_FOUND = "ERR_PKG_NOT_FOUND"
    const val ERROR_APP_INFO_FAILED = "ERR_APP_INFO"
    const val ERROR_APP_LIST_FAILED = "ERR_APP_LIST"
    const val ERROR_TIMEOUT = "ERR_TIMEOUT"
    const val ERROR_INVALID_ARG = "ERR_INVALID_ARG"
    const val ERROR_ADB_INIT = "ERR_ADB_INIT"
    const val ERROR_IOS_SIMULATOR = "ERR_IOS_SIMULATOR"
    const val ERROR_NO_SIMULATOR = "ERR_NO_SIMULATOR"
    const val ERROR_APP_NOT_FOUND = "ERR_APP_NOT_FOUND"
    const val ERROR_UNKNOWN = "ERR_UNKNOWN"

    // Retry configuration defaults
    private const val DEFAULT_MAX_ATTEMPTS = 3
    private const val DEFAULT_INITIAL_DELAY_MS = 500L

    /**
     * Handles tool errors and produces a standardized result with error codes.
     * Logging behavior varies based on the configured log level:
     * - PRODUCTION: Only error message and code
     * - DEVELOPMENT: Error message, code, and sanitized stack trace
     * - DEBUG: Full stack trace with all details
     */
    fun handleToolError(e: Exception, logger: Logger, context: String): CallToolResult {
        val (errorCode, errorMessage) = when (e) {
            // Android-specific errors
            is NoDeviceAvailableException -> Pair(ERROR_DEVICE_NOT_FOUND, DEVICE_NOT_FOUND)
            is CommandExecutionException -> Pair(ERROR_COMMAND_FAILED, "Command execution failed (code: ${e.exitCode}): ${e.message}")
            is PackageNotFoundException -> Pair(ERROR_PACKAGE_NOT_FOUND, "Package not found: ${e.message}")
            is AdbInitializationException -> Pair(ERROR_ADB_INIT, "ADB initialization failed: ${e.message}")

            // iOS-specific errors
            is NoSimulatorAvailableException -> Pair(ERROR_NO_SIMULATOR, "No iOS simulator available: ${e.message}")
            is IOSSimulatorException -> Pair(ERROR_IOS_SIMULATOR, "iOS simulator error: ${e.message}")
            is AppNotFoundException -> Pair(ERROR_APP_NOT_FOUND, "App not found: ${e.message}")

            // Common errors
            is AppInfoException -> Pair(ERROR_APP_INFO_FAILED, "Failed to get app information: ${e.message}")
            is AppListException -> Pair(ERROR_APP_LIST_FAILED, "Failed to list apps: ${e.message}")
            is TimeoutException -> Pair(ERROR_TIMEOUT, "Operation timed out: ${e.message}")
            is IllegalArgumentException -> Pair(ERROR_INVALID_ARG, "Invalid argument: ${e.message}")

            else -> Pair(ERROR_UNKNOWN, "Error: ${e.message ?: e.javaClass.simpleName}")
        }

        val config = AppConfig.createDefault()

        // Log based on configured level
        when (config.logLevel) {
            LogLevel.PRODUCTION -> {
                // Production: minimal logging, no stack traces
                logger.warn("{}: [{}] {}", context, errorCode, errorMessage)
            }
            LogLevel.DEVELOPMENT -> {
                // Development: detailed logging with sanitized stack trace
                logger.warn("{}: [{}] {}", context, errorCode, errorMessage)
                logger.warn("Exception type: {}", e.javaClass.simpleName)
                // Only log the first 3 stack trace elements to avoid exposing full internal paths
                val sanitizedTrace = e.stackTrace.take(3).joinToString("\n  ") {
                    "${it.className}.${it.methodName}:${it.lineNumber}"
                }
                logger.warn("Stack trace (sanitized):\n  {}", sanitizedTrace)
            }
            LogLevel.DEBUG -> {
                // Debug: full logging including complete stack traces
                logger.warn("{}: [{}] {}", context, errorCode, errorMessage)
                logger.warn("Full stack trace:", e)
            }
        }

        return CallToolResult(
            content = listOf(TextContent("$errorMessage [Code: $errorCode]"))
        )
    }

    /**
     * Attempts to retry an operation that might fail transiently.
     */
    suspend fun <T> retryOperation(
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
        operation: suspend () -> T
    ): T {
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            try {
                return operation()
            } catch (e: Exception) {
                lastException = e

                if (attempt < maxAttempts) {
                    // Exponential backoff
                    val delayMs = initialDelayMs * (1 shl (attempt - 1))
                    delay(delayMs)
                }
            }
        }

        throw lastException ?: RuntimeException("Operation failed after $maxAttempts attempts")
    }
}