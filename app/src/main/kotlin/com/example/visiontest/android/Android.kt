package com.example.visiontest.android

import com.example.visiontest.AdbInitializationException
import com.example.visiontest.AppInfoException
import com.example.visiontest.AppListException
import com.example.visiontest.CommandExecutionException
import com.example.visiontest.NoDeviceAvailableException
import com.example.visiontest.PackageNotFoundException
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.common.DeviceType
import com.example.visiontest.common.MobileDevice
import com.example.visiontest.utils.ErrorHandler
import com.malinskiy.adam.AndroidDebugBridgeClientFactory
import com.malinskiy.adam.interactor.StartAdbInteractor
import com.malinskiy.adam.request.device.Device
import com.malinskiy.adam.request.device.DeviceState
import com.malinskiy.adam.request.device.ListDevicesRequest
import com.malinskiy.adam.request.shell.v2.ShellCommandRequest
import com.malinskiy.adam.request.shell.v2.ShellCommandResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

class Android(
    private val timeoutMillis: Long = 5000L,
    private val cacheValidityPeriod: Long = 1000L,
    private val logger: Logger = LoggerFactory.getLogger(Android::class.java),
) : DeviceConfig, AutoCloseable {

    companion object {
        // Android shell command error patterns
        private val LAUNCH_ERROR_PATTERNS = listOf(
            "Error:",           // Generic am error prefix
            "Exception:",       // Java exceptions
            "does not exist",   // Package/activity not found
            "CRASHED",          // App crash during launch
            "monkey aborted"    // Monkey tool failure
        )
    }

    private val adb = AndroidDebugBridgeClientFactory().build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        runBlocking {
            try {
                StartAdbInteractor().execute()
            } catch (e: Exception) {
                logger.error("Error while starting ADB: ${e.message}")
                throw AdbInitializationException("Unable to start ADB", e)
            }
        }
    }

    override fun close() {
        try {
            scope.cancel()
        } catch (e: Exception) {
            logger.error("Error while closing ADB: ${e.message}")
        }
    }

    private val deviceListCacheLock = Mutex()
    private var deviceListCache: List<MobileDevice>? = null
    private var lastDeviceListFetch: Long = 0

    private suspend fun fetchDevices(): List<MobileDevice> {
        return deviceListCacheLock.withLock {
            val currentTime = System.currentTimeMillis()

            // Use cache if valid
            if (deviceListCache != null && (currentTime - lastDeviceListFetch) < cacheValidityPeriod) {
                return@withLock deviceListCache!!
            }

            // Cache invalid or missing - fetch new device list
            val devices = withTimeout(timeoutMillis) {
                adb.execute(ListDevicesRequest())
            }
            val activeDevices = devices.filter { it.state == DeviceState.DEVICE }

            // Update the cache
            deviceListCache = activeDevices.map { it.toMobileDevice() }
            lastDeviceListFetch = currentTime

            activeDevices.map { it.toMobileDevice() }
        }
    }

    override suspend fun getFirstAvailableDevice(): MobileDevice {
        return ErrorHandler.retryOperation(maxAttempts = 3) {
            fetchDevices().firstOrNull()
                ?: throw NoDeviceAvailableException("no Android devices available")
        }
    }

    override suspend fun listDevices(): List<MobileDevice> {
        return fetchDevices()
    }

    private suspend fun shell(
        command: String,
        deviceSerial: String? = null
    ): String {
        val device = deviceSerial?.let { serial ->
            fetchDevices().find { it.id == serial }
        } ?: getFirstAvailableDevice()

        logger.debug("Executing command: '$command' on device: ${device.id}")

        val response: ShellCommandResult = withTimeoutOrNull(timeoutMillis) {
            adb.execute(ShellCommandRequest(command), device.id)
        } ?: throw TimeoutException("Timeout executing command: $command")

        if (response.exitCode != 0) {
            logger.warn("Error executing command: '$command', exit code: ${response.exitCode}")
            logger.warn("Output error: ${response.errorOutput}")
            throw CommandExecutionException(
                "Error executing command: $command",
                response.exitCode
            )
        }

        return response.output.trim()
    }

    override suspend fun executeShell(command: String, deviceId: String?): String {
        return shell(command, deviceId)
    }

    override suspend fun listApps(deviceId: String?): List<String> {
        return ErrorHandler.retryOperation {
            try {
                val result = shell("pm list packages")

                result
                    .split("\n")
                    .filter { it.startsWith("package:") }
                    .map { it.trim() }
                    .map { it.substring("package:".length) }
                    .distinct()
            } catch (e: Exception) {
                logger.error("Error listing apps: ${e.message}")
                throw AppListException("Error listing apps", e)
            }
        }
    }

    override suspend fun getAppInfo(packageName: String, deviceId: String?): String {
        if (!isValidPackageName(packageName)) {
            throw IllegalArgumentException("Invalid package name: $packageName")
        }

        return try {
            val result = shell("dumpsys package $packageName")
            if (result.contains("Unable to find package")) {
                throw PackageNotFoundException("Package not found: $packageName")
            }
            result
        } catch (e: CommandExecutionException) {
            throw AppInfoException("Cannot find information for $packageName", e)
        }
    }

    /**
     * Validates Android package name according to official naming rules:
     * - Must have at least two segments separated by dots
     * - Each segment must start with a letter
     * - Segments can contain letters, digits, and underscores
     * - No consecutive dots or trailing/leading dots
     */
    private fun isValidPackageName(packageName: String): Boolean {
        return packageName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]+)+$"))
    }

    /**
     * Validates that an activity name follows Android naming conventions
     * and doesn't contain shell metacharacters. (; | & $ ( ) < > ` \ " ' \n \r)
     */
    private fun isValidActivityName(activityName: String): Boolean {
        return activityName.matches(Regex("\\.?[a-zA-Z][a-zA-Z0-9_.]*")) &&
               !activityName.contains(Regex("[;|&$()<>`\\\\\"'\\n\\r]"))
    }

    override suspend fun launchApp(packageName: String, activityName: String?, deviceId: String?): Boolean {
        if (!isValidPackageName(packageName)) {
            throw IllegalArgumentException("Invalid package name: $packageName")
        }

        if (activityName != null && !isValidActivityName(activityName)) {
            throw IllegalArgumentException("Invalid activity name: $activityName")
        }

        val command = if (activityName != null) {
            "am start -n $packageName/$activityName"
        } else {
            "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        }

        val result = shell(command)

        // Check for specific error patterns from am/monkey commands
        if (LAUNCH_ERROR_PATTERNS.any { result.contains(it, ignoreCase = true) }) {
            logger.error("Failed to launch app {}: {}", packageName, result)
            throw CommandExecutionException("Error launching app: $packageName", exitCode = -1)
        }

        logger.debug("Successfully launched app: {}", packageName)
        return true
    }

    private fun Device.toMobileDevice(): MobileDevice {
        return MobileDevice(
            id = this.serial,
            name = this.serial,
            type = DeviceType.ANDROID,
            state = this.state.toString()
        )
    }
}
