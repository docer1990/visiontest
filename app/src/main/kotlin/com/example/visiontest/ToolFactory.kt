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
import kotlinx.serialization.json.int
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
        registerAndroidTapByCoordinatesTool(server)
        registerAndroidPressBackTool(server)
        registerAndroidPressHomeTool(server)
        registerAndroidSwipe(server)
        registerAndroidSwipeDirection(server)
        registerAndroidSwipeOnElement(server)
        registerAndroidGetDeviceInfoTool(server)
        registerGetInteractiveElementsTool(server)

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
            description = """
                Gets the COMPLETE UI hierarchy as XML from the current screen.
                The automation server must be running first (use start_automation_server).

                PREFER 'get_interactive_elements' for most tasks - it returns a cleaner,
                filtered list of elements you can actually interact with.

                USE THIS TOOL WHEN YOU NEED:
                - Full XML structure with parent-child relationships
                - Debug why an element isn't found by get_interactive_elements
                - Analyze layout containers and view hierarchy
                - Find elements with unusual/custom class names
                - Inspect raw accessibility properties

                RETURNS: Verbose XML with ALL elements including:
                - Layout containers (FrameLayout, LinearLayout, etc.)
                - Invisible or disabled elements
                - Every node in the accessibility tree

                TIP: Start with get_interactive_elements. Only use this if you need
                the full structure or can't find what you're looking for.
            """.trimIndent(),
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

                FLUTTER APP TIP: Flutter apps expose text labels via 'contentDescription' instead of 'text'.
                If you cannot find an element by 'text', retry using 'contentDescription' with the same value.
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

    private fun registerAndroidTapByCoordinatesTool(server: Server) {
        server.addTool(
            name = "android_tap_by_coordinates",
            description = """
                Tap on the Android device screen at the specified (x, y) coordinates.
                The automation server must be running first (use start_automation_server).

                WORKFLOW: First call 'get_ui_hierarchy' or 'find_element' to locate the target element.
                Elements have bounds in format [left,top][right,bottom] (e.g., [100,200][300,400]).
                Calculate center coordinates: x = (left + right) / 2, y = (top + bottom) / 2.

                Example: For bounds [100,200][300,400], tap at x=200, y=300.

                USE CASES:
                - Tap buttons, links, or interactive elements after locating them
                - Tap at specific screen positions for gestures or navigation

                TIP: If you know the element's text or resourceId, use 'find_element' first
                to get precise bounds rather than guessing coordinates.
                """.trimIndent(),
            inputSchema = Tool.Input(
                required = listOf("x", "y")
            )
        ) { request: CallToolRequest ->
            try {
                val result = runWithTimeout(10000) {
                    if (!automationClient.isServerRunning()) {
                        return@runWithTimeout "Automation server is not running. Use 'start_automation_server' first."
                    }

                    val x = request.arguments["x"]?.jsonPrimitive?.int
                        ?: return@runWithTimeout "Error: Missing 'x' parameter"
                    val y = request.arguments["y"]?.jsonPrimitive?.int
                        ?: return@runWithTimeout "Error: Missing 'y' parameter"

                    automationClient.tapByCoordinates(x, y)
                }

                CallToolResult(
                    content = listOf(TextContent(result))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error performing tap by coordinates")
            }
        }
    }

    private fun registerAndroidSwipe(server: Server) {
        server.addTool(
            name = "android_swipe",
            description = """
                Swipe on the Android device screen from one point to another.
                The automation server must be running first (use start_automation_server).

                PARAMETERS:
                - startX, startY: Starting coordinates of the swipe
                - endX, endY: Ending coordinates of the swipe
                - steps (optional, default 20): Number of steps in the swipe gesture.
                  Controls speed and smoothness: lower = faster (10 for quick), higher = slower (50-100 for scrolling).

                USE CASES:
                - Scroll lists: swipe from bottom to top (e.g., startY=1500, endY=500)
                - Navigate carousels: swipe horizontally
                - Pull-to-refresh: swipe from top to bottom

                EXAMPLE: To scroll down on a 1080x1920 screen, swipe from (540, 1400) to (540, 600).
            """.trimIndent(),
            inputSchema = Tool.Input(
                required = listOf("startX", "startY", "endX", "endY")
            )
        ) { request: CallToolRequest ->
            try {
                val result = runWithTimeout(10000) {
                    if (!automationClient.isServerRunning()) {
                        return@runWithTimeout "Automation server is not running. Use 'start_automation_server' first."
                    }

                    val startXElement = request.arguments["startX"]
                        ?: return@runWithTimeout "Error: Missing 'startX' parameter"
                    val startX = startXElement.jsonPrimitive.content.toIntOrNull()
                        ?: return@runWithTimeout "Error: 'startX' must be an integer"
                    val startYElement = request.arguments["startY"]
                        ?: return@runWithTimeout "Error: Missing 'startY' parameter"
                    val startY = startYElement.jsonPrimitive.content.toIntOrNull()
                        ?: return@runWithTimeout "Error: 'startY' must be an integer"
                    val endXElement = request.arguments["endX"]
                        ?: return@runWithTimeout "Error: Missing 'endX' parameter"
                    val endX = endXElement.jsonPrimitive.content.toIntOrNull()
                        ?: return@runWithTimeout "Error: 'endX' must be an integer"
                    val endYElement = request.arguments["endY"]
                        ?: return@runWithTimeout "Error: Missing 'endY' parameter"
                    val endY = endYElement.jsonPrimitive.content.toIntOrNull()
                        ?: return@runWithTimeout "Error: 'endY' must be an integer"
                    val steps = request.arguments["steps"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20

                    automationClient.swipe(startX, startY, endX, endY, steps)
                }
                CallToolResult(
                    content = listOf(TextContent(result))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error performing swipe")
            }
        }
    }

    private fun registerAndroidSwipeDirection(server: Server) {
        server.addTool(
            name = "android_swipe_direction",
            description = """
                Swipe in a direction on the Android device screen.
                The automation server must be running first (use start_automation_server).

                SIMPLER than 'android_swipe' - no need to calculate coordinates!
                Automatically uses screen center and calculates start/end points.

                PARAMETERS:
                - direction (required): "up", "down", "left", "right"
                - distance (optional): "short" (20%), "medium" (40%, default), "long" (60%)
                - speed (optional): "slow", "normal" (default), "fast"

                DIRECTION BEHAVIOR:
                - "up"    → Finger moves up, content scrolls DOWN (see more below)
                - "down"  → Finger moves down, content scrolls UP (see more above)
                - "left"  → Finger moves left (next item in carousel)
                - "right" → Finger moves right (previous item / go back)

                EXAMPLES:
                - Scroll a list down: direction="up"
                - Scroll a list up: direction="down"
                - Next carousel item: direction="left"
                - Pull to refresh: direction="down", distance="long", speed="slow"

                USE 'android_swipe' instead when you need:
                - Precise start/end coordinates
                - Swipe from a specific element
                - Diagonal swipes
            """.trimIndent(),
            inputSchema = Tool.Input(
                required = listOf("direction")
            )
        ) { request: CallToolRequest ->
            try {
                val result = runWithTimeout(10000) {
                    if (!automationClient.isServerRunning()) {
                        return@runWithTimeout "Automation server is not running. Use 'start_automation_server' first."
                    }

                    val direction = request.arguments["direction"]?.jsonPrimitive?.content
                        ?: return@runWithTimeout "Error: Missing 'direction' parameter"

                    val validDirections = listOf("up", "down", "left", "right")
                    if (direction.lowercase() !in validDirections) {
                        return@runWithTimeout "Error: Invalid direction '$direction'. Must be one of: ${validDirections.joinToString()}"
                    }

                    val distance = request.arguments["distance"]?.jsonPrimitive?.content ?: "medium"
                    val speed = request.arguments["speed"]?.jsonPrimitive?.content ?: "normal"

                    automationClient.swipeByDirection(direction, distance, speed)
                }
                CallToolResult(
                    content = listOf(TextContent(result))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error performing swipe by direction")
            }
        }
    }

    private fun registerAndroidSwipeOnElement(server: Server) {
        server.addTool(
            name = "android_swipe_on_element",
            description = """
                Swipe on a specific UI element in a direction.
                The automation server must be running first (use start_automation_server).

                PERFECT FOR:
                - Carousels and horizontal scrollers
                - ViewPagers and tab swiping
                - Sliders and seek bars
                - Any scrollable element that isn't full-screen

                PARAMETERS:
                - direction (required): "up", "down", "left", "right"
                - At least ONE selector (to find the element):
                  - resourceId: e.g., "com.example:id/carousel"
                  - text: Exact text match
                  - textContains: Partial text match
                  - className: e.g., "androidx.recyclerview.widget.RecyclerView"
                  - contentDescription: Accessibility label
                - speed (optional): "slow", "normal" (default), "fast"

                HOW IT WORKS:
                1. Finds the element using the provided selector
                2. Calculates swipe coordinates within the element's bounds
                3. Performs the swipe (70% of element dimension)

                EXAMPLES:
                - Carousel next: resourceId="carousel", direction="left"
                - Carousel previous: resourceId="carousel", direction="right"
                - Scroll list in container: className="RecyclerView", direction="up"

                USE 'android_swipe_direction' for full-screen scrolling.
                USE 'android_swipe' for precise coordinate control.
            """.trimIndent(),
            inputSchema = Tool.Input(
                required = listOf("direction")
            )
        ) { request: CallToolRequest ->
            try {
                val result = runWithTimeout(10000) {
                    if (!automationClient.isServerRunning()) {
                        return@runWithTimeout "Automation server is not running. Use 'start_automation_server' first."
                    }

                    val direction = request.arguments["direction"]?.jsonPrimitive?.content
                        ?: return@runWithTimeout "Error: Missing 'direction' parameter"

                    val validDirections = listOf("up", "down", "left", "right")
                    if (direction.lowercase() !in validDirections) {
                        return@runWithTimeout "Error: Invalid direction '$direction'. Must be one of: ${validDirections.joinToString()}"
                    }

                    val text = request.arguments["text"]?.jsonPrimitive?.content
                    val textContains = request.arguments["textContains"]?.jsonPrimitive?.content
                    val resourceId = request.arguments["resourceId"]?.jsonPrimitive?.content
                    val className = request.arguments["className"]?.jsonPrimitive?.content
                    val contentDescription = request.arguments["contentDescription"]?.jsonPrimitive?.content

                    if (text == null && textContains == null && resourceId == null &&
                        className == null && contentDescription == null) {
                        return@runWithTimeout "Error: At least one selector required (text, textContains, resourceId, className, or contentDescription)"
                    }

                    val speed = request.arguments["speed"]?.jsonPrimitive?.content ?: "normal"

                    automationClient.swipeOnElement(
                        direction = direction,
                        text = text,
                        textContains = textContains,
                        resourceId = resourceId,
                        className = className,
                        contentDescription = contentDescription,
                        speed = speed
                    )
                }
                CallToolResult(
                    content = listOf(TextContent(result))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error performing swipe on element")
            }
        }
    }

    private fun registerAndroidPressBackTool(server: Server) {
        server.addTool(
            name = "android_press_back",
            description = """
                Press the hardware back button on the Android device.
                The automation server must be running first (use start_automation_server).

                USE CASES:
                - Navigate to the previous screen in an app
                - Dismiss dialogs, popups, or bottom sheets
                - Close the on-screen keyboard
                - Exit full-screen or immersive modes
                - Cancel ongoing operations (e.g., close a search bar)

                BEHAVIOR:
                - Equivalent to pressing the physical/virtual back button
                - Apps may intercept this action for custom behavior
                - Multiple presses may be needed to fully exit nested screens

                TIP: Use 'get_ui_hierarchy' after pressing back to verify the expected
                screen is now displayed before proceeding with further actions.
            """.trimIndent(),
            inputSchema = Tool.Input()
        ) { _: CallToolRequest ->
            try {
                val result = runWithTimeout(10000) {
                    if (!automationClient.isServerRunning()) {
                        return@runWithTimeout "Automation server is not running. Use 'start_automation_server' first."
                    }

                    automationClient.pressBack()
                }

                CallToolResult(
                    content = listOf(TextContent(result))
                )
            }  catch (e: Exception) {
                handleToolError(e, "Error pressing back")
            }
        }
    }

    private fun registerAndroidPressHomeTool(server: Server) {
        server.addTool(
            name = "android_press_home",
            description = """
                Press the hardware home button on the Android device.
                The automation server must be running first (use start_automation_server).

                USE CASES:
                - Return to the device home screen from any app
                - Minimize the current app without closing it
                - Exit immersive or full-screen modes
                - Reset navigation state to a known starting point
                - Switch context before launching a different app

                BEHAVIOR:
                - Equivalent to pressing the physical/virtual home button
                - The current app moves to the background (not terminated)
                - Always navigates to the launcher/home screen
                - Works regardless of app state or navigation depth

                TIP: Use 'get_ui_hierarchy' after pressing home to confirm you're on the
                home screen, then use 'launch_app_android' to start a different app.
            """.trimIndent(),
            inputSchema = Tool.Input()
        ) { _: CallToolRequest ->
            try {
                val result = runWithTimeout(10000) {
                    if (!automationClient.isServerRunning()) {
                        return@runWithTimeout "Automation server is not running. Use 'start_automation_server' first."
                    }

                    automationClient.pressHome()
                }

                CallToolResult(
                    content = listOf(TextContent(result))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error pressing home")
            }
        }
    }

    private fun registerAndroidGetDeviceInfoTool(server: Server) {
        server.addTool(
            name = "android_get_device_info",
            description = """
                Gets device information from the connected Android device via the automation server.
                The automation server must be running first (use start_automation_server).

                RETURNS:
                - Display size (width x height in pixels)
                - Display rotation (0, 90, 180, or 270 degrees)
                - SDK version (Android API level)

                USE CASES:
                - Determine screen dimensions for calculating tap/swipe coordinates
                - Check device orientation before performing gestures
                - Verify SDK version for feature compatibility
            """.trimIndent(),
            inputSchema = Tool.Input()
        ) {
            try {
                val result = runWithTimeout {
                    if (!automationClient.isServerRunning()) {
                        return@runWithTimeout "Automation server is not running. Use 'start_automation_server' first."
                    }

                    automationClient.getDeviceInfo()
                }

                CallToolResult(
                    content = listOf(TextContent(result))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error getting device info")
            }
        }
    }

    private fun registerGetInteractiveElementsTool(server: Server) {
        server.addTool(
            name = "get_interactive_elements",
            description = """
                Gets a filtered list of interactive UI elements from the current screen.
                The automation server must be running first (use start_automation_server).

                MUCH MORE USEFUL than 'get_ui_hierarchy' for most tasks because it:
                - Returns only elements you can actually interact with
                - Filters out layout containers and invisible elements
                - Provides center coordinates ready for tapping
                - Returns clean JSON instead of verbose XML

                HEURISTICS USED (handles missing accessibility properties):
                - Explicitly interactive: clickable, checkable, scrollable, long-clickable
                - Known interactive classes: Button, EditText, CheckBox, Switch, etc.
                - Has meaningful content: text, content-description, or resource-id
                - Excludes: layout containers, invisible elements, disabled elements

                OPTIONAL PARAMETERS:
                - includeDisabled: Set to true to also include disabled elements (default: false)

                RETURNS for each element:
                - text, contentDescription, resourceId, className
                - bounds (e.g., "[100,200][300,400]")
                - centerX, centerY (ready for android_tap_by_coordinates)
                - isClickable, isCheckable, isScrollable, isLongClickable, isEnabled

                WORKFLOW:
                1. Call get_interactive_elements to see what you can interact with
                2. Find the element you want by text, contentDescription, or resourceId
                3. Use centerX, centerY with android_tap_by_coordinates to tap it
            """.trimIndent(),
            inputSchema = Tool.Input()
        ) { request: CallToolRequest ->
            try {
                val result = runWithTimeout(30000) {
                    if (!automationClient.isServerRunning()) {
                        return@runWithTimeout "Automation server is not running. Use 'start_automation_server' first."
                    }

                    val includeDisabledRaw = request.arguments["includeDisabled"]
                        ?.jsonPrimitive?.content
                    val includeDisabled = when (includeDisabledRaw) {
                        null -> false
                        "true" -> true
                        "false" -> false
                        else -> return@runWithTimeout "Invalid value for 'includeDisabled': '$includeDisabledRaw'. Must be true or false."
                    }

                    automationClient.getInteractiveElements(includeDisabled)
                }

                CallToolResult(
                    content = listOf(TextContent(result))
                )
            } catch (e: Exception) {
                handleToolError(e, "Error getting interactive elements")
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