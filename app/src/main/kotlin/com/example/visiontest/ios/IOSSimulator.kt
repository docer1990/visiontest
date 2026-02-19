package com.example.visiontest.ios

import com.example.visiontest.AppNotFoundException
import com.example.visiontest.IOSSimulatorException
import com.example.visiontest.NoSimulatorAvailableException
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.common.DeviceType
import com.example.visiontest.common.MobileDevice
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class IOSSimulator(
    private val processExecutor: ProcessExecutor = ProcessExecutor(),
    private val logger: Logger = LoggerFactory.getLogger(IOSSimulator::class.java)
) : DeviceConfig {

    companion object {
        private const val SIMCTL = "xcrun"
        private const val STATE_BOOTED = "Booted"
        private const val STATE_SHUTDOWN = "Shutdown"

        // Shell metacharacters that could be used for command injection
        private val DANGEROUS_SHELL_CHARS = Regex("[;|&$()<>`\\\\\"'\\n\\r]")

        // Maximum allowed command length to prevent abuse
        private const val MAX_COMMAND_LENGTH = 1000
    }

    override suspend fun listDevices(): List<MobileDevice> {
        val result = processExecutor.execute(SIMCTL, "simctl", "list", "devices", "available", "--json")
        if (result.exitCode != 0) {
            throw IOSSimulatorException("Failed to list devices: ${result.errorOutput}")
        }

        return parseDeviceList(result.output)
    }

    override suspend fun getFirstAvailableDevice(): MobileDevice {
        val devices = listDevices()

        return devices.firstOrNull { it.state == STATE_BOOTED }
            ?: devices.firstOrNull { it.state == STATE_SHUTDOWN }
            ?: throw NoSimulatorAvailableException("No iOS simulator available")
    }

    override suspend fun listApps(deviceId: String?): List<String> {
        val device = getDevice(deviceId)
        ensureDeviceBooted(device)

        // simctl listapps returns plist format, we need to parse it differently
        val result = processExecutor.execute(SIMCTL, "simctl", "listapps", device.id)

        if (result.exitCode != 0) {
            throw IOSSimulatorException("Failed to list apps: ${result.errorOutput}")
        }

        // Parse the plist-formatted output
        return parseAppListFromPlist(result.output)
    }

    override suspend fun getAppInfo(packageName: String, deviceId: String?): String {
        // Security: Validate bundle ID format
        if (!isValidBundleId(packageName)) {
            throw IllegalArgumentException("Invalid bundle ID format: $packageName")
        }

        val device = getDevice(deviceId)
        ensureDeviceBooted(device)

        val result = processExecutor.execute(SIMCTL, "simctl", "get_app_container", device.id, packageName)

        if (result.exitCode != 0) {
            throw AppNotFoundException("App not found: $packageName")
        }

        return "Bundle ID: $packageName\nContainer Path: ${result.output}"
    }

    override suspend fun launchApp(
        packageName: String,
        activityName: String?,
        deviceId: String?
    ): Boolean {
        // Security: Validate bundle ID format
        if (!isValidBundleId(packageName)) {
            throw IllegalArgumentException("Invalid bundle ID format: $packageName")
        }

        val device = getDevice(deviceId)
        ensureDeviceBooted(device)

        val result = processExecutor.execute(SIMCTL, "simctl", "launch", device.id, packageName)

        if (result.exitCode != 0) {
            throw IOSSimulatorException("Failed to launch app: ${result.errorOutput}")
        }

        logger.debug("Successfully launched app: {}", packageName)
        return true
    }

    override suspend fun executeShell(command: String, deviceId: String?): String {
        // Security: Validate command to prevent injection attacks
        if (!isValidShellCommand(command)) {
            throw IllegalArgumentException(
                "Invalid shell command: contains dangerous characters or exceeds maximum length"
            )
        }

        val device = getDevice(deviceId)
        ensureDeviceBooted(device)

        val result = processExecutor.execute(SIMCTL, "simctl", "spawn", device.id, "sh", "-c", command)

        if (result.exitCode != 0) {
            throw IOSSimulatorException("Command failed: ${result.errorOutput}")
        }

        return result.output
    }

    /**
     * Retrieves a device by ID, or gets the first available device if no ID is provided.
     * This helper method reduces code duplication across iOS device operations.
     *
     * @param deviceId Optional device ID to look up
     * @return The requested device or first available device
     * @throws NoSimulatorAvailableException if no device is available
     */
    private suspend fun getDevice(deviceId: String?): MobileDevice {
        return deviceId?.let {
            listDevices().find { device -> device.id == deviceId }
                ?: throw NoSimulatorAvailableException("Device with ID $deviceId not found")
        } ?: getFirstAvailableDevice()
    }

    /**
     * Validates that a shell command is safe to execute.
     * Prevents command injection by blocking dangerous metacharacters.
     *
     * @param command The command to validate
     * @return true if the command is safe, false otherwise
     */
    internal fun isValidShellCommand(command: String): Boolean {
        if (command.isBlank()) {
            logger.warn("Empty shell command rejected")
            return false
        }

        if (command.length > MAX_COMMAND_LENGTH) {
            logger.warn("Shell command exceeds maximum length: {} > {}", command.length, MAX_COMMAND_LENGTH)
            return false
        }

        if (DANGEROUS_SHELL_CHARS.containsMatchIn(command)) {
            logger.warn("Shell command contains dangerous characters: {}", command)
            return false
        }

        return true
    }

    /**
     * Validates iOS bundle identifier format.
     * Bundle IDs follow reverse-DNS notation (e.g., com.example.app)
     *
     * Rules:
     * - Must have at least two segments separated by dots
     * - Each segment must start with a letter or underscore
     * - Segments can contain letters, digits, underscores, and hyphens
     * - No consecutive dots or trailing/leading dots
     *
     * @param bundleId The bundle identifier to validate
     * @return true if valid, false otherwise
     */
    internal fun isValidBundleId(bundleId: String): Boolean {
        if (bundleId.isBlank()) {
            return false
        }

        // iOS bundle ID pattern: at least 2 segments, alphanumeric with underscores/hyphens
        val pattern = Regex("^[a-zA-Z_][a-zA-Z0-9_-]*(?:\\.[a-zA-Z_][a-zA-Z0-9_-]*)+$")
        return bundleId.matches(pattern)
    }

    private suspend fun ensureDeviceBooted(device: MobileDevice) {
        if (device.state != STATE_BOOTED) {
            logger.info("Booting simulator: {}", device.name)
            val result = processExecutor.execute(SIMCTL, "simctl", "boot", device.id)
            if (result.exitCode != 0) {
                throw IOSSimulatorException("Failed to boot simulator: ${result.errorOutput}")
            }
        }
    }

    /**
     * Parses the property list (plist) formatted output from simctl listapps.
     * The output format is like: { "com.apple.app" = { ... }; "com.other.app" = { ... }; }
     * Extracts all bundle IDs (keys) from the plist.
     */
    internal fun parseAppListFromPlist(plistOutput: String): List<String> {
        // Extract all bundle IDs using regex
        // Pattern matches quoted strings that are keys in the plist (followed by = or :)
        val bundleIdPattern = Regex(""""([a-zA-Z][a-zA-Z0-9._-]*(?:\.[a-zA-Z][a-zA-Z0-9._-]*)+)"\s*[=:]""")

        val bundleIds = bundleIdPattern.findAll(plistOutput)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        logger.debug("Found {} apps in plist output", bundleIds.size)
        return bundleIds
    }

    /**
     * Legacy JSON parsing method - kept for potential future use if Apple adds JSON output.
     */
    @Suppress("unused")
    private fun parseAppList(jsonOutput: String): List<String> {
        val json = Json.parseToJsonElement(jsonOutput).jsonObject
        return json.keys.toList()
    }

    internal fun parseDeviceList(jsonOutput: String): List<MobileDevice> {
        val devices = mutableListOf<MobileDevice>()
        val jsonElement = Json.parseToJsonElement(jsonOutput)
        val devicesMap = jsonElement.jsonObject["devices"]?.jsonObject ?: return emptyList()

        for ((runtime, deviceArray) in devicesMap) {
            val osVersion = runtime.substringAfterLast("iOS-").replace("-", ".")

            deviceArray.jsonArray.forEach { deviceJson ->
                val deviceObj = deviceJson.jsonObject

                devices.add(
                    MobileDevice(
                        id = deviceObj["udid"]?.jsonPrimitive?.content ?: "",
                        name = deviceObj["name"]?.jsonPrimitive?.content ?: "Unknown",
                        type = DeviceType.IOS_SIMULATOR,
                        osVersion = osVersion,
                        state = deviceObj["state"]?.jsonPrimitive?.content ?: "Unknown",
                        modelName = deviceObj["deviceTypeIdentifier"]?.jsonPrimitive?.content
                            ?.substringAfterLast(".") ?: "Unknown"
                    )
                )
            }
        }

        return devices
    }
}