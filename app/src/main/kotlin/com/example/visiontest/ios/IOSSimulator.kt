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
    // Separate executor for `simctl bootstatus`: a cold simulator boot takes far
    // longer than the default 5s command timeout.
    private val bootWaitExecutor: ProcessExecutor = ProcessExecutor(timeoutMillis = BOOT_WAIT_TIMEOUT_MILLIS),
    private val logger: Logger = LoggerFactory.getLogger(IOSSimulator::class.java)
) : DeviceConfig {

    companion object {
        private const val SIMCTL = "xcrun"
        private const val STATE_BOOTED = "Booted"
        private const val STATE_SHUTDOWN = "Shutdown"

        // Maximum time to wait for a simulator to finish booting (simctl bootstatus)
        internal const val BOOT_WAIT_TIMEOUT_MILLIS = 120_000L
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

    /**
     * Arbitrary shell execution is intentionally not supported on iOS.
     *
     * The previous implementation spawned `sh -c <command>` inside the simulator with a
     * metacharacter blacklist, which still allowed arbitrary binaries to run on the host
     * with the user's privileges (e.g. `rm -rf <path>` contains no metacharacters).
     * No MCP tool or CLI command needs this capability on iOS, so it is disabled rather
     * than hardened.
     */
    override suspend fun executeShell(command: String, deviceId: String?): String {
        throw UnsupportedOperationException(
            "Shell execution is not supported on iOS simulators for security reasons"
        )
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

            // `simctl boot` returns before the simulator is usable; block until boot
            // completes (-b) so the next simctl command doesn't fail intermittently.
            val bootStatus = try {
                bootWaitExecutor.execute(SIMCTL, "simctl", "bootstatus", device.id, "-b")
            } catch (e: CommandTimeoutException) {
                throw IOSSimulatorException(
                    "Simulator did not finish booting within ${BOOT_WAIT_TIMEOUT_MILLIS / 1000}s", e
                )
            }
            if (bootStatus.exitCode != 0) {
                throw IOSSimulatorException("Failed waiting for simulator boot: ${bootStatus.errorOutput}")
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
            // simctl also lists watchOS/tvOS/visionOS runtimes; only iOS simulators
            // are usable by this server (runtime keys look like
            // "com.apple.CoreSimulator.SimRuntime.iOS-17-2").
            if (!runtime.contains(".iOS-")) continue

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