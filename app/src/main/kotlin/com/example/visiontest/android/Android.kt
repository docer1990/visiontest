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
import kotlinx.coroutines.withContext
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

        // Shell metacharacters that could be used for command injection
        // Same pattern as IOSSimulator for consistency
        private val DANGEROUS_SHELL_CHARS = Regex("[;|&$()<>`\\\\\"'\\n\\r]")

        // Allowlist of permitted ADB subcommands
        private val ALLOWED_ADB_COMMANDS = setOf("install", "forward", "shell")
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

    /**
     * Executes an ADB command on the host machine (not a shell command on the device).
     * Only allows specific safe commands: install, forward, shell (for am instrument only).
     *
     * Security: Uses allowlist of commands and validates all arguments against
     * dangerous shell metacharacters to prevent command injection.
     *
     * @param args ADB command arguments (e.g., "install", "-r", "/path/to/apk")
     * @param deviceSerial Optional device serial to target
     * @throws IllegalArgumentException if command is not in the allowlist or arguments are invalid
     * @throws CommandExecutionException if ADB command fails
     */
    suspend fun executeAdb(vararg args: String, deviceSerial: String? = null): String {
        require(args.isNotEmpty()) { "ADB command cannot be empty" }

        val subCommand = args[0]
        val subArgs = args.drop(1)

        // Validate command against allowlist
        require(subCommand in ALLOWED_ADB_COMMANDS) {
            "ADB command '$subCommand' is not allowed. Permitted: $ALLOWED_ADB_COMMANDS"
        }

        // Validate and sanitize arguments based on command type
        when (subCommand) {
            "install" -> validateInstallArgs(subArgs)
            "forward" -> validateForwardArgs(subArgs)
            "shell" -> validateShellArgs(subArgs)
        }

        val device = deviceSerial?.let { serial ->
            fetchDevices().find { it.id == serial }
        } ?: getFirstAvailableDevice()

        // Build command with validated arguments - ProcessBuilder handles escaping
        val command = mutableListOf("adb", "-s", device.id)
        command.addAll(args)

        logger.debug("Executing ADB command: ${command.joinToString(" ")}")

        return withContext(Dispatchers.IO) {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                logger.warn("ADB command failed with exit code $exitCode: $output")
                throw CommandExecutionException("ADB command failed: ${args.joinToString(" ")}", exitCode)
            }

            output.trim()
        }
    }

    private fun validateInstallArgs(args: List<String>) {
        // Allowed flags for install
        val allowedFlags = setOf("-r", "-t", "-d", "-g")
        val filteredArgs = args.filter { it !in allowedFlags }

        require(filteredArgs.size == 1) { "install command requires exactly one APK path" }

        val apkPath = filteredArgs[0]
        require(apkPath.endsWith(".apk")) { "Install path must be an APK file" }
        require(!DANGEROUS_SHELL_CHARS.containsMatchIn(apkPath)) {
            "APK path contains dangerous characters"
        }
        require(java.io.File(apkPath).exists()) { "APK file does not exist: $apkPath" }
    }

    private fun validateForwardArgs(args: List<String>) {
        val tcpPattern = Regex("^tcp:\\d{1,5}$")

        if (args.firstOrNull() == "--remove") {
            require(args.size == 2) { "forward --remove requires one tcp:port argument" }
            require(args[1].matches(tcpPattern)) { "Invalid port format: ${args[1]}" }
        } else {
            require(args.size == 2) { "forward command requires two tcp:port arguments" }
            require(args.all { it.matches(tcpPattern) }) { "Invalid port format in forward command" }
            // Validate port range (non-privileged ports only)
            args.forEach { arg ->
                val port = arg.removePrefix("tcp:").toIntOrNull()
                require(port != null && port in 1024..65535) {
                    "Port must be between 1024 and 65535"
                }
            }
        }
    }

    private fun validateShellArgs(args: List<String>) {
        // Only allow "am instrument" for automation server startup
        require(args.size >= 2 && args[0] == "am" && args[1] == "instrument") {
            "Only 'am instrument' shell commands are allowed via executeAdb"
        }
        // Validate all arguments don't contain dangerous shell metacharacters
        args.forEach { arg ->
            require(!DANGEROUS_SHELL_CHARS.containsMatchIn(arg)) {
                "Shell argument contains dangerous characters: $arg"
            }
        }
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
