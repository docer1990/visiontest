package com.example.visiontest

import com.example.visiontest.android.Android
import com.example.visiontest.android.AutomationClient
import com.example.visiontest.ios.IOSManager
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.utils.ErrorHandler
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.Logger
import com.example.visiontest.utils.ErrorHandler.PACKAGE_NAME_REQUIRED
import com.example.visiontest.utils.ErrorHandler.BUNDLE_ID_REQUIRED
import com.example.visiontest.config.AutomationConfig
import java.io.File



class ToolFactory(
    private val android: DeviceConfig,
    private val ios: DeviceConfig,
    private val logger: Logger,
    private val toolTimeoutMillis: Long = 10000L,
    private val automationClient: AutomationClient = AutomationClient()
) {

    companion object {
        // Android system property keys
        private const val PROP_MODEL = "ro.product.model"
        private const val PROP_ANDROID_VERSION = "ro.build.version.release"
        private const val PROP_SDK_VERSION = "ro.build.version.sdk"
    }

    fun registerAllTools(server: Server) {
        // Android tools
        registerAndroidAvailableDeviceTool(server)
        registerAndroidListAppsTool(server)
        registerAndroidInfoAppTool(server)
        registerAndroidLaunchAppTool(server)
        registerInstallAutomationServerTool(server)
        registerStartAutomationServerTool(server)
        registerAutomationServerStatusTool(server)
        registerGetUiHierarchyTool(server)
        registerFindElementTool(server)

        // iOS tools
        registerIOSAvailableDeviceTool(server)
        registerIOSListAppsTool(server)
        registerIOSInfoAppTool(server)
        registerIOSLaunchAppTool(server)
    }

    private fun registerAndroidAvailableDeviceTool(server: Server) {
        server.addTool(
            name = "available_device_android",
            description = "Returns detailed information about the first available Android device, including model, Android version, SDK version, and device state. Automatically selects the first active device connected via ADB.",
            inputSchema = Tool.Input()
        ) {
            try {
                val result = runWithTimeout {
                    android.getFirstAvailableDevice()
                }
                // Fetch additional device information
                val deviceProps = runWithTimeout {
                    android.executeShell("getprop", result.id)
                }
                val modelName = extractProperty(deviceProps, PROP_MODEL)
                val androidVersion = extractProperty(deviceProps, PROP_ANDROID_VERSION)
                val sdkVersion = extractProperty(deviceProps, PROP_SDK_VERSION)

                val deviceInfo = """
                |Device found:
                |Serial: ${result.id}
                |Model: $modelName
                |Android Version: $androidVersion
                |SDK Version: $sdkVersion
                |State: ${result.state}
            """.trimMargin()

                CallToolResult(
                    content = listOf(TextContent(deviceInfo))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error finding available device")
            }
        }
    }

    private fun registerAndroidListAppsTool(server: Server) {
        server.addTool(
            name = "list_apps_android",
            description = "Returns a complete list of all applications installed on the Android device. Returns package names (e.g., com.example.app) for all installed apps.",
            inputSchema = Tool.Input()
        ) {
            try {
                val result = runWithTimeout {
                    android.listApps()
                }

                val formattedResult = if (result.isEmpty()) {
                    "No apps found on the device"
                } else {
                    "Found these apps: ${result.joinToString(", ")}"
                }

                CallToolResult(
                    content = listOf(TextContent(formattedResult))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error listing apps")
            }
        }
    }

    private fun registerAndroidInfoAppTool(server: Server) {
        server.addTool(
            name = "info_app_android",
            description = "Returns detailed information about a specific Android application. Requires 'packageName' parameter (e.g., com.example.app). Returns version info, SDK requirements, installation dates, and permissions.",
            inputSchema = Tool.Input(
                required = listOf("packageName")
            )
        ) { request: CallToolRequest ->
            try {
                val packageName = request.arguments["packageName"]?.jsonPrimitive?.content
                    ?: return@addTool CallToolResult(
                        content = listOf(TextContent(PACKAGE_NAME_REQUIRED))
                    )

                val rawResult = runWithTimeout {
                    android.getAppInfo(packageName)
                }

                val formattedInfo = formatAppInfo(rawResult, packageName)

                CallToolResult(
                    content = listOf(TextContent(formattedInfo))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error retrieving app information")
            }
        }
    }

    private fun registerAndroidLaunchAppTool(server: Server) {
        server.addTool(
            name = "launch_app_android",
            description = "Launches an Android application on the connected device. Requires 'packageName' parameter (e.g., com.example.app). Uses monkey command to launch the app's main activity.",
            inputSchema = Tool.Input(
                required = listOf("packageName")
            )
        ) { request: CallToolRequest ->
            try {
                val packageName = request.arguments["packageName"]?.jsonPrimitive?.content
                    ?: return@addTool CallToolResult(
                        content = listOf(TextContent(PACKAGE_NAME_REQUIRED))
                    )

                val result = runWithTimeout {
                    android.launchApp(packageName)
                }

                val message = if (result) {
                    "Successfully launched the app: $packageName"
                } else {
                    "Failed to launch the app: $packageName"
                }

                CallToolResult(
                    content = listOf(TextContent(message))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error launching app")
            }
        }
    }

    private fun <T> runWithTimeout(block: suspend () -> T): T {
        return runBlocking {
            withTimeout(toolTimeoutMillis) {
                block()
            }
        }
    }

    private fun <T> runWithTimeout(timeoutMs: Long, block: suspend () -> T): T {
        return runBlocking {
            withTimeout(timeoutMs) {
                block()
            }
        }
    }

    private fun handleToolError(e: Exception, context: String): CallToolResult {
        return ErrorHandler.handleToolError(e, logger, context)
    }

    // Helper function to extract properties from getprop output
    private fun extractProperty(propOutput: String, propName: String): String {
        val regex = Regex("\\[$propName]: \\[(.+?)]")
        return regex.find(propOutput)?.groupValues?.get(1) ?: "Unknown"
    }

    private fun formatAppInfo(rawInfo: String, packageName: String): String {
        // Extract useful information using regex patterns
        val versionName = extractPattern(rawInfo, "versionName=(\\S+)")
        val versionCode = extractPattern(rawInfo, "versionCode=(\\d+)")
        val firstInstallTime = extractPattern(rawInfo, "firstInstallTime=(\\S+)")
        val lastUpdateTime = extractPattern(rawInfo, "lastUpdateTime=(\\S+)")
        val targetSdk = extractPattern(rawInfo, "targetSdk=(\\d+)")
        val minSdk = extractPattern(rawInfo, "minSdk=(\\d+)")

        // Extract permissions
        val permissions = Regex("grantedPermissions:(.*?)(?=\\n\\n)", RegexOption.DOT_MATCHES_ALL)
            .find(rawInfo)?.groupValues?.get(1)
            ?.split("\n")
            ?.filter { it.contains("permission.") }
            ?.map { it.trim() }
            ?.take(10) // Limit to first 10 permissions
            ?.joinToString("\n  - ") ?: "None"

        return """
        |App Information for $packageName:
        |-------------------------
        |Version: $versionName (Code: $versionCode)
        |SDK: Target=$targetSdk, Minimum=$minSdk
        |Installation: 
        |  - First Installed: $firstInstallTime
        |  - Last Updated: $lastUpdateTime
        |
        |Key Permissions (first 10):
        |  - $permissions
        |${if (permissions != "None") "\n[Additional permissions omitted for brevity]" else ""}
    """.trimMargin()
    }

    private fun extractPattern(text: String, pattern: String): String {
        return Regex(pattern).find(text)?.groupValues?.get(1) ?: "Unknown"
    }

    private fun registerIOSAvailableDeviceTool(server: Server) {
        server.addTool(
            name = "ios_available_device",
            description = "Returns detailed information about the first available iOS device or simulator. Includes device ID, name, type, state (Booted/Shutdown), iOS version, and model. Prioritizes booted simulators over shutdown ones.",
            inputSchema = Tool.Input()
        ) {
            try {
                val device = runWithTimeout {
                    ios.getFirstAvailableDevice()
                }

                val deviceInfo = """
                  |iOS Device found:
                  |ID: ${device.id}
                  |Name: ${device.name}
                  |Type: ${device.type}
                  |State: ${device.state}
                  |OS Version: ${device.osVersion ?: "Unknown"}
                  |Model: ${device.modelName ?: "Unknown"}
              """.trimMargin()

                CallToolResult(
                    content = listOf(TextContent(deviceInfo))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error finding available iOS device")
            }
        }
    }

    private fun registerIOSListAppsTool(server: Server) {
        server.addTool(
            name = "ios_list_apps",
            description = "Returns a complete list of all applications installed on the iOS device or simulator. Returns bundle IDs (e.g., com.apple.mobilesafari) for all installed apps. Device must be booted.",
            inputSchema = Tool.Input()
        ) {
            try {
                val result = runWithTimeout {
                    ios.listApps()
                }

                val formattedResult = if (result.isEmpty()) {
                    "No apps found on the iOS device"
                } else {
                    "Found these apps: ${result.joinToString(", ")}"
                }

                CallToolResult(
                    content = listOf(TextContent(formattedResult))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error listing iOS apps")
            }
        }
    }

    private fun registerIOSInfoAppTool(server: Server) {
        server.addTool(
            name = "ios_info_app",
            description = "Returns detailed information about a specific iOS application. Requires 'bundleId' parameter (e.g., com.apple.mobilesafari). Returns bundle ID and app container path. Device must be booted.",
            inputSchema = Tool.Input(
                required = listOf("bundleId")
            )
        ) { request: CallToolRequest ->
            try {
                val bundleId = request.arguments["bundleId"]?.jsonPrimitive?.content
                    ?: return@addTool CallToolResult(
                        content = listOf(TextContent(BUNDLE_ID_REQUIRED))
                    )

                val rawResult = runWithTimeout {
                    ios.getAppInfo(bundleId)
                }

                val formattedInfo = "App Information for $bundleId:\n$rawResult"

                CallToolResult(
                    content = listOf(TextContent(formattedInfo))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error retrieving iOS app information")
            }
        }
    }

    private fun registerIOSLaunchAppTool(server: Server) {
        server.addTool(
            name = "ios_launch_app",
            description = "Launches an iOS application on the device or simulator. Requires 'bundleId' parameter (e.g., com.apple.mobilesafari). Device must be booted before launching apps.",
            inputSchema = Tool.Input(
                required = listOf("bundleId")
            )
        ) { request: CallToolRequest ->
            try {
                val bundleId = request.arguments["bundleId"]?.jsonPrimitive?.content
                    ?: return@addTool CallToolResult(
                        content = listOf(TextContent(BUNDLE_ID_REQUIRED))
                    )

                val result = runWithTimeout {
                    ios.launchApp(bundleId)
                }

                val message = if (result) {
                    "Successfully launched the iOS app: $bundleId"
                } else {
                    "Failed to launch the iOS app: $bundleId"
                }

                CallToolResult(
                    content = listOf(TextContent(message))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error launching iOS app")
            }
        }
    }

    private fun registerInstallAutomationServerTool(server: Server) {
        server.addTool(
            name = "install_automation_server",
            description = "Installs the automation server APKs on the connected Android device. Run this once before using start_automation_server.",
            inputSchema = Tool.Input()
        ) {
            try {
                val result = runWithTimeout {
                    // Check if device is available
                    val device = android.getFirstAvailableDevice()
                    logger.info("Installing automation server on device: ${device.id}")

                    // Find the APK file
                    val apkPath = findAutomationServerApk()
                    if (apkPath == null) {
                        return@runWithTimeout "Automation server APK not found. Please build it first with: ./gradlew :automation-server:assembleDebug :automation-server:assembleDebugAndroidTest"
                    }

                    // Install the APKs
                    val androidDevice = android as? Android
                        ?: return@runWithTimeout "Android device configuration not available"

                    // Install main APK
                    // Test APK: .../apk/androidTest/debug/automation-server-debug-androidTest.apk
                    // Main APK: .../apk/debug/automation-server-debug.apk
                    val mainApkPath = apkPath
                        .replace("androidTest/", "")
                        .replace("-androidTest", "")
                    if (File(mainApkPath).exists()) {
                        androidDevice.executeAdb("install", "-r", mainApkPath)
                        logger.info("Installed main APK: $mainApkPath")
                    } else {
                        return@runWithTimeout "Main APK not found at: $mainApkPath"
                    }

                    // Install test APK
                    androidDevice.executeAdb("install", "-r", apkPath)
                    logger.info("Installed test APK: $apkPath")

                    "Automation server APKs installed successfully on device ${device.id}. Use 'start_automation_server' to start the server."
                }

                CallToolResult(
                    content = listOf(TextContent(result))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error installing automation server")
            }
        }
    }

    private fun registerStartAutomationServerTool(server: Server) {
        server.addTool(
            name = "start_automation_server",
            description = "Starts the automation server on the connected Android device. The APKs must be installed first using install_automation_server. Sets up port forwarding and starts the instrumentation server.",
            inputSchema = Tool.Input()
        ) {
            try {
                val result = runWithTimeout(30000) {
                    val device = android.getFirstAvailableDevice()
                    val androidDevice = android as? Android
                        ?: return@runWithTimeout "Android device configuration not available"

                    val port = AutomationConfig.DEFAULT_PORT

                    // Check if already running
                    if (automationClient.isServerRunning()) {
                        return@runWithTimeout "Automation server is already running on localhost:$port"
                    }

                    // Set up port forwarding
                    androidDevice.executeAdb("forward", "tcp:$port", "tcp:$port")
                    logger.info("Port forwarding set up: tcp:$port -> tcp:$port")

                    // Start instrumentation in background using nohup
                    // We use shell to run in background so it doesn't block
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val command = listOf(
                            "adb", "-s", device.id, "shell",
                            "am", "instrument", "-w",
                            "-e", "port", port.toString(),
                            "-e", "class", AutomationConfig.AUTOMATION_SERVER_TEST_CLASS,
                            "${AutomationConfig.AUTOMATION_SERVER_TEST_PACKAGE}/${AutomationConfig.INSTRUMENTATION_RUNNER}"
                        )
                        logger.info("Starting instrumentation: ${command.joinToString(" ")}")

                        ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .start()
                    }

                    // Wait for server to start and verify via health check
                    var attempts = 0
                    val maxAttempts = 10
                    while (attempts < maxAttempts) {
                        kotlinx.coroutines.delay(500)
                        if (automationClient.isServerRunning()) {
                            logger.info("Automation server started successfully")
                            return@runWithTimeout "Automation server started successfully on device ${device.id}. Server is listening on localhost:$port"
                        }
                        attempts++
                        logger.debug("Waiting for server to start... attempt $attempts/$maxAttempts")
                    }

                    "Automation server may not have started properly. Check device logs with: adb logcat | grep AutomationServer"
                }

                CallToolResult(
                    content = listOf(TextContent(result))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error starting automation server")
            }
        }
    }

    private fun registerAutomationServerStatusTool(server: Server) {
        server.addTool(
            name = "automation_server_status",
            description = "Checks if the automation server is running on the connected Android device. Returns server status and connection information.",
            inputSchema = Tool.Input()
        ) {
            try {
                val isRunning = runWithTimeout {
                    automationClient.isServerRunning()
                }
                
                val statusMessage = if (isRunning) {
                    "Automation server is running and accessible at localhost:${AutomationConfig.DEFAULT_PORT}"
                } else {
                    "Automation server is not running. Use 'start_automation_server' to start it."
                }
                
                CallToolResult(
                    content = listOf(TextContent(statusMessage))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error checking automation server status")
            }
        }
    }

    private fun registerGetUiHierarchyTool(server: Server) {
        server.addTool(
            name = "get_ui_hierarchy",
            description = "Gets the UI hierarchy (all visible elements) from the current screen of the connected Android device. Returns XML with element bounds, text, resource IDs, and other properties. The automation server must be running first (use start_automation_server).",
            inputSchema = Tool.Input()
        ) {
            try {
                val result = runWithTimeout(30000) {
                    if (!automationClient.isServerRunning()) {
                        return@runWithTimeout "Automation server is not running. Use 'start_automation_server' first."
                    }

                    automationClient.getUiHierarchy()
                }

                CallToolResult(
                    content = listOf(TextContent(result))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error getting UI hierarchy")
            }
        }
    }

    private fun registerFindElementTool(server: Server) {
        server.addTool(
            name = "find_element",
            description = """
                Finds a UI element on the current screen of the connected Android device.
                Returns element info including bounds, text, and properties if found.
                The automation server must be running first (use start_automation_server).

                Provide at least ONE of these parameters:
                - text: Exact text match
                - textContains: Partial text match
                - resourceId: Resource ID (e.g., "com.example:id/button")
                - className: Class name (e.g., "android.widget.Button")
                - contentDescription: Accessibility content description
            """.trimIndent(),
            inputSchema = Tool.Input()
        ) { request: CallToolRequest ->
            try {
                val result = runWithTimeout(30000) {
                    if (!automationClient.isServerRunning()) {
                        return@runWithTimeout "Automation server is not running. Use 'start_automation_server' first."
                    }

                    val args = request.arguments
                    val text = args["text"]?.jsonPrimitive?.content
                    val textContains = args["textContains"]?.jsonPrimitive?.content
                    val resourceId = args["resourceId"]?.jsonPrimitive?.content
                    val className = args["className"]?.jsonPrimitive?.content
                    val contentDescription = args["contentDescription"]?.jsonPrimitive?.content

                    if (text == null && textContains == null && resourceId == null &&
                        className == null && contentDescription == null) {
                        return@runWithTimeout "Error: At least one selector required (text, textContains, resourceId, className, or contentDescription)"
                    }

                    automationClient.findElement(
                        text = text,
                        textContains = textContains,
                        resourceId = resourceId,
                        className = className,
                        contentDescription = contentDescription
                    )
                }

                CallToolResult(
                    content = listOf(TextContent(result))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error finding element")
            }
        }
    }

    private fun findAutomationServerApk(): String? {
        val apkRelativePath = "automation-server/build/outputs/apk/androidTest/debug/automation-server-debug-androidTest.apk"

        logger.debug("Searching for automation server APK...")
        logger.debug("Current working directory: ${File(".").absolutePath}")

        // 1. Check environment variable first (allows explicit configuration)
        System.getenv("VISION_TEST_APK_PATH")?.let { envPath ->
            val file = File(envPath)
            if (file.exists()) {
                logger.info("Using APK from VISION_TEST_APK_PATH: $envPath")
                return file.absolutePath
            }
            logger.warn("VISION_TEST_APK_PATH set but file not found: $envPath")
        }

        // 2. Try relative to current working directory
        val cwdFile = File(apkRelativePath)
        if (cwdFile.exists()) {
            logger.info("Found APK relative to CWD: ${cwdFile.absolutePath}")
            return cwdFile.absolutePath
        }

        // 3. Try relative to code source location (JAR or classes directory)
        val codeSourceRoot = findCodeSourceRoot()
        if (codeSourceRoot != null) {
            val codeSourceApk = File(codeSourceRoot, apkRelativePath)
            logger.debug("Checking code source path: ${codeSourceApk.absolutePath}")
            if (codeSourceApk.exists()) {
                logger.info("Found APK relative to code source: ${codeSourceApk.absolutePath}")
                return codeSourceApk.absolutePath
            }
        }

        // 4. Try to find project root by looking for settings.gradle.kts from CWD
        val projectRoot = findProjectRoot(File(".").absoluteFile)
        if (projectRoot != null) {
            val projectApk = File(projectRoot, apkRelativePath)
            logger.debug("Checking project root path: ${projectApk.absolutePath}")
            if (projectApk.exists()) {
                logger.info("Found APK relative to project root: ${projectApk.absolutePath}")
                return projectApk.absolutePath
            }
        }

        logger.warn("APK not found. Searched locations:")
        logger.warn("  - CWD: ${File(".").absolutePath}")
        logger.warn("  - Code source root: $codeSourceRoot")
        logger.warn("  - Project root: $projectRoot")
        logger.warn("Set VISION_TEST_APK_PATH environment variable to specify the APK location explicitly.")
        return null
    }

    private fun findCodeSourceRoot(): File? {
        return try {
            val codeSource = this::class.java.protectionDomain?.codeSource
            val location = codeSource?.location?.toURI()?.let { File(it) }

            if (location != null) {
                logger.debug("Code source location: ${location.absolutePath}")

                // If running from JAR (app/build/libs/visiontest.jar), go up 3 levels to project root
                if (location.isFile && location.name.endsWith(".jar")) {
                    return location.parentFile?.parentFile?.parentFile
                }

                // If running from classes dir (app/build/classes/kotlin/main), go up 5 levels
                if (location.isDirectory && location.path.contains("build/classes")) {
                    return location.parentFile?.parentFile?.parentFile?.parentFile?.parentFile
                }

                // Try going up until we find settings.gradle.kts
                return findProjectRoot(location)
            }
            null
        } catch (e: Exception) {
            logger.debug("Could not determine code source location: ${e.message}")
            null
        }
    }

    private fun findProjectRoot(startFrom: File): File? {
        var current = startFrom.absoluteFile
        // Handle trailing "." in path
        if (current.name == ".") {
            current = current.parentFile ?: return null
        }

        repeat(10) {
            val settingsKts = File(current, "settings.gradle.kts")
            val settingsGroovy = File(current, "settings.gradle")
            logger.debug("Checking for settings.gradle in: ${current.absolutePath}")

            if (settingsKts.exists() || settingsGroovy.exists()) {
                logger.debug("Found project root: ${current.absolutePath}")
                return current
            }
            current = current.parentFile ?: return null
        }
        return null
    }

}