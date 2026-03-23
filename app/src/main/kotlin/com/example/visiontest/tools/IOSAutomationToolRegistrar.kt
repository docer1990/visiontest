package com.example.visiontest.tools

import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.config.IOSAutomationConfig
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.ios.IOSAutomationClient
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.Logger

class IOSAutomationToolRegistrar(
    private val ios: DeviceConfig,
    private val iosAutomationClient: IOSAutomationClient,
    private val discovery: ToolDiscovery,
    private val logger: Logger
) : ToolRegistrar {

    @Volatile
    private var iosXcodebuildProcess: Process? = null

    override fun registerTools(scope: ToolScope) {
        registerStartAutomationServer(scope)
        registerAutomationServerStatus(scope)
        registerGetUiHierarchy(scope)
        registerGetInteractiveElements(scope)
        registerTapByCoordinates(scope)
        registerSwipe(scope)
        registerSwipeDirection(scope)
        registerFindElement(scope)
        registerGetDeviceInfo(scope)
        registerPressHome(scope)
        registerInputText(scope)
        registerStopAutomationServer(scope)
    }

    // ==================== Server Process Management ====================

    internal fun buildXcodebuildCommand(
        xctestrunPath: String?,
        projectPath: String?,
        simulatorName: String
    ): List<String> {
        val testId = "${IOSAutomationConfig.UI_TEST_TARGET}/${IOSAutomationConfig.TEST_CLASS}/${IOSAutomationConfig.TEST_METHOD}"
        return if (xctestrunPath != null) {
            listOf(
                "xcodebuild", "test-without-building",
                "-xctestrun", xctestrunPath,
                "-destination", "platform=iOS Simulator,name=$simulatorName",
                "-only-testing:$testId"
            )
        } else {
            val resolvedProject = requireNotNull(projectPath) {
                "projectPath must be set when xctestrunPath is null"
            }
            listOf(
                "xcodebuild", "test",
                "-project", resolvedProject,
                "-scheme", IOSAutomationConfig.XCODE_SCHEME,
                "-destination", "platform=iOS Simulator,name=$simulatorName",
                "-only-testing:$testId"
            )
        }
    }

    private data class ServerPollResult(
        val message: String,
        val earlyExitCode: Int? = null
    )

    /** Formats a command list as a shell-safe string, quoting arguments that contain spaces or special characters. */
    private fun shellQuote(command: List<String>): String {
        return command.joinToString(" ") { arg ->
            if (arg.any { it.isWhitespace() || it in setOf('(', ')', '&', '|', ';', '*', '?', '<', '>', '$', '!', '`', '"') }) {
                "'${arg.replace("'", "'\\''")}'"
            } else {
                arg
            }
        }
    }

    private suspend fun startAndPollServer(
        command: List<String>,
        maxAttempts: Int,
        port: Int,
        label: String
    ): ServerPollResult {
        withContext(Dispatchers.IO) {
            logger.info("Starting iOS automation server ($label): ${command.joinToString(" ")}")
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            iosXcodebuildProcess = process
        }

        var attempts = 0
        while (attempts < maxAttempts) {
            delay(2000)

            val process = iosXcodebuildProcess
            if (process != null && !process.isAlive) {
                val exitCode = process.exitValue()
                logger.warn("xcodebuild ($label) exited early with code $exitCode")
                iosXcodebuildProcess = null
                return ServerPollResult(
                    message = "xcodebuild ($label) exited with code $exitCode before the server started. " +
                        "Run manually to see errors:\n${shellQuote(command)}",
                    earlyExitCode = exitCode
                )
            }

            if (iosAutomationClient.isServerRunning()) {
                logger.info("iOS automation server started successfully ($label)")
                return ServerPollResult("iOS automation server started successfully ($label). Server is listening on localhost:$port")
            }
            attempts++
            logger.debug("Waiting for iOS server to start ($label)... attempt $attempts/$maxAttempts")
        }

        return ServerPollResult("iOS automation server did not respond after ${maxAttempts * 2}s. xcodebuild may still be building. Check with 'ios_automation_server_status' or run xcodebuild manually to see output.")
    }

    // ==================== Tool Registrations ====================

    private fun registerStartAutomationServer(scope: ToolScope) {
        scope.tool(
            name = "ios_start_automation_server",
            description = """
                Starts the iOS automation server on the booted iOS simulator.
                Uses pre-built test bundle if available, otherwise builds from source.

                The server starts on port ${IOSAutomationConfig.DEFAULT_PORT} and is directly
                accessible at localhost (no port forwarding needed for iOS simulators).
            """.trimIndent(),
            timeoutMs = 200000
        ) {
            val port = IOSAutomationConfig.DEFAULT_PORT

            if (iosAutomationClient.isServerRunning()) {
                return@tool "iOS automation server is already running on localhost:$port"
            }

            // Clean up any orphaned previous process
            iosXcodebuildProcess?.let { process ->
                if (process.isAlive) {
                    logger.info("Destroying orphaned xcodebuild process before starting a new one")
                    process.destroyForcibly()
                }
                iosXcodebuildProcess = null
            }

            // Discover launch path: pre-built bundle preferred, source build as fallback
            val xctestrunPath = discovery.findXctestrun()
            val projectPath = discovery.findXcodeProject()

            if (xctestrunPath == null && projectPath == null) {
                return@tool "Neither pre-built iOS test bundle nor Xcode source project found. " +
                    "To fix: re-run install.sh on macOS to download the pre-built bundle, " +
                    "or clone the VisionTest repository and set ${IOSAutomationConfig.XCODE_PROJECT_PATH_ENV} " +
                    "to build from source."
            }

            val usingPrebuilt = xctestrunPath != null
            if (usingPrebuilt) {
                logger.info("Using pre-built iOS test bundle: $xctestrunPath")
            } else {
                logger.info("Using source build from Xcode project: $projectPath")
            }

            val device = ios.getFirstAvailableDevice()
            val simulatorName = device.name

            val command = buildXcodebuildCommand(xctestrunPath, projectPath, simulatorName)
            val maxAttempts = if (usingPrebuilt) 30 else 60

            val label = if (usingPrebuilt) "pre-built bundle" else "source build"
            val primaryResult = startAndPollServer(command, maxAttempts, port, label)

            if (primaryResult.earlyExitCode == null) {
                return@tool primaryResult.message
            }

            // Primary attempt exited early — try source build fallback if available
            if (usingPrebuilt && projectPath != null) {
                logger.warn("Pre-built bundle failed (exit code ${primaryResult.earlyExitCode}), falling back to source build")
                val fallbackCommand = buildXcodebuildCommand(null, projectPath, simulatorName)
                val fallbackResult = startAndPollServer(fallbackCommand, 60, port, "source build fallback")
                return@tool fallbackResult.message
            }

            primaryResult.message
        }
    }

    private fun registerAutomationServerStatus(scope: ToolScope) {
        scope.tool(
            name = "ios_automation_server_status",
            description = "Checks if the iOS automation server is running on the simulator. Returns server status and connection information."
        ) {
            val isRunning = iosAutomationClient.isServerRunning()
            if (isRunning) {
                "iOS automation server is running and accessible at localhost:${IOSAutomationConfig.DEFAULT_PORT}"
            } else {
                "iOS automation server is not running. Use 'ios_start_automation_server' to start it."
            }
        }
    }

    private fun registerGetUiHierarchy(scope: ToolScope) {
        scope.tool(
            name = "ios_get_ui_hierarchy",
            description = """
                Gets the COMPLETE UI hierarchy as XML from the current iOS simulator screen.
                The iOS automation server must be running first (use ios_start_automation_server).

                PREFER 'ios_get_interactive_elements' for most tasks - it returns a cleaner,
                filtered list of elements you can actually interact with.

                USE THIS TOOL WHEN YOU NEED:
                - Full XML structure with parent-child relationships
                - Debug why an element isn't found by ios_get_interactive_elements
                - Analyze layout structure
                - Inspect raw accessibility properties

                OPTIONAL PARAMETERS:
                - bundleId: Bundle ID of the app to query (e.g., "com.apple.Preferences").
                  If not provided, queries springboard (which only shows system UI, not app content).
                  ALWAYS provide bundleId when inspecting an app's UI.
            """.trimIndent(),
            timeoutMs = 30000
        ) { request ->
            if (!iosAutomationClient.isServerRunning()) {
                return@tool "iOS automation server is not running. Use 'ios_start_automation_server' first."
            }
            val bundleId = request.optionalString("bundleId")
            iosAutomationClient.getUiHierarchy(bundleId)
        }
    }

    private fun registerGetInteractiveElements(scope: ToolScope) {
        scope.tool(
            name = "ios_get_interactive_elements",
            description = """
                Gets a filtered list of interactive UI elements from the current iOS simulator screen.
                The iOS automation server must be running first (use ios_start_automation_server).

                Returns only elements you can interact with (buttons, text fields, switches, etc.)
                with center coordinates ready for tapping via ios_tap_by_coordinates.

                OPTIONAL PARAMETERS:
                - includeDisabled: Set to true to include disabled elements (default: false)
                - bundleId: Bundle ID of the app to query (e.g., "com.apple.Preferences").
                  If not provided, queries springboard (which only shows system UI, not app content).
                  ALWAYS provide bundleId when inspecting an app's UI.

                WORKFLOW:
                1. Call ios_get_interactive_elements with bundleId to see what you can interact with
                2. Find the element by text, label, or identifier
                3. Use centerX, centerY with ios_tap_by_coordinates to tap it
            """.trimIndent(),
            timeoutMs = 30000
        ) { request ->
            if (!iosAutomationClient.isServerRunning()) {
                return@tool "iOS automation server is not running. Use 'ios_start_automation_server' first."
            }

            val includeDisabledRaw = request.optionalString("includeDisabled")
            val includeDisabled = when (includeDisabledRaw) {
                null -> false
                "true" -> true
                "false" -> false
                else -> return@tool "Invalid value for 'includeDisabled': '$includeDisabledRaw'. Must be true or false."
            }
            val bundleId = request.optionalString("bundleId")

            iosAutomationClient.getInteractiveElements(includeDisabled, bundleId)
        }
    }

    private fun registerTapByCoordinates(scope: ToolScope) {
        scope.tool(
            name = "ios_tap_by_coordinates",
            description = """
                Tap on the iOS simulator screen at the specified (x, y) coordinates.
                The iOS automation server must be running first (use ios_start_automation_server).

                WORKFLOW: First call 'ios_get_interactive_elements' to locate the target element.
                Use the centerX, centerY values from the returned elements.
            """.trimIndent(),
            inputSchema = Tool.Input(required = listOf("x", "y"))
        ) { request ->
            if (!iosAutomationClient.isServerRunning()) {
                return@tool "iOS automation server is not running. Use 'ios_start_automation_server' first."
            }
            val x = request.requireInt("x")
            val y = request.requireInt("y")
            iosAutomationClient.tapByCoordinates(x, y)
        }
    }

    private fun registerSwipe(scope: ToolScope) {
        scope.tool(
            name = "ios_swipe",
            description = """
                Swipe on the iOS simulator screen from one point to another.
                The iOS automation server must be running first (use ios_start_automation_server).

                PARAMETERS:
                - startX, startY: Starting coordinates
                - endX, endY: Ending coordinates
                - steps (optional, default 20): Controls speed (maps to duration: steps * 0.05 seconds)
            """.trimIndent(),
            inputSchema = Tool.Input(required = listOf("startX", "startY", "endX", "endY"))
        ) { request ->
            if (!iosAutomationClient.isServerRunning()) {
                return@tool "iOS automation server is not running. Use 'ios_start_automation_server' first."
            }
            val startX = request.requireInt("startX")
            val startY = request.requireInt("startY")
            val endX = request.requireInt("endX")
            val endY = request.requireInt("endY")
            val steps = request.optionalInt("steps") ?: 20
            iosAutomationClient.swipe(startX, startY, endX, endY, steps)
        }
    }

    private fun registerSwipeDirection(scope: ToolScope) {
        scope.tool(
            name = "ios_swipe_direction",
            description = """
                Swipe in a direction on the iOS simulator screen.
                The iOS automation server must be running first (use ios_start_automation_server).

                SIMPLER than 'ios_swipe' - no need to calculate coordinates!

                PARAMETERS:
                - direction (required): "up", "down", "left", "right"
                - distance (optional): "short" (20%), "medium" (40%, default), "long" (60%)
                - speed (optional): "slow", "normal" (default), "fast"

                DIRECTION BEHAVIOR:
                - "up"    → Finger moves up, content scrolls DOWN
                - "down"  → Finger moves down, content scrolls UP
                - "left"  → Finger moves left (next item)
                - "right" → Finger moves right (previous item)
            """.trimIndent(),
            inputSchema = Tool.Input(required = listOf("direction"))
        ) { request ->
            if (!iosAutomationClient.isServerRunning()) {
                return@tool "iOS automation server is not running. Use 'ios_start_automation_server' first."
            }

            val direction = request.requireString("direction")
            val validDirections = listOf("up", "down", "left", "right")
            if (direction.lowercase() !in validDirections) {
                return@tool "Error: Invalid direction '$direction'. Must be one of: ${validDirections.joinToString()}"
            }

            val distance = request.optionalString("distance") ?: "medium"
            val speed = request.optionalString("speed") ?: "normal"

            iosAutomationClient.swipeByDirection(direction, distance, speed)
        }
    }

    private fun registerFindElement(scope: ToolScope) {
        scope.tool(
            name = "ios_find_element",
            description = """
                Finds a UI element on the current iOS simulator screen.
                Returns element info including bounds, text, and properties if found.
                The iOS automation server must be running first (use ios_start_automation_server).

                Provide at least ONE of these parameters:
                - text: Exact text match
                - textContains: Partial text match
                - resourceId: Accessibility identifier
                - className: Element type name (e.g., "Button", "TextField")
                - contentDescription: Accessibility label
                - bundleId: Bundle ID of the app to search in (e.g., "com.apple.Preferences").
                  If not provided, searches springboard. ALWAYS provide bundleId when searching in an app.
            """.trimIndent(),
            timeoutMs = 30000
        ) { request ->
            if (!iosAutomationClient.isServerRunning()) {
                return@tool "iOS automation server is not running. Use 'ios_start_automation_server' first."
            }

            val text = request.optionalString("text")
            val textContains = request.optionalString("textContains")
            val identifier = request.optionalString("resourceId")
            val elementType = request.optionalString("className")
            val label = request.optionalString("contentDescription")
            val bundleId = request.optionalString("bundleId")

            if (text == null && textContains == null && identifier == null &&
                elementType == null && label == null) {
                return@tool "Error: At least one selector required (text, textContains, resourceId, className, or contentDescription)"
            }

            iosAutomationClient.findElement(
                text = text,
                textContains = textContains,
                identifier = identifier,
                elementType = elementType,
                label = label,
                bundleId = bundleId
            )
        }
    }

    private fun registerGetDeviceInfo(scope: ToolScope) {
        scope.tool(
            name = "ios_get_device_info",
            description = """
                Gets device information from the iOS simulator via the automation server.
                The iOS automation server must be running first (use ios_start_automation_server).

                RETURNS:
                - Display size (width x height in pixels)
                - Display rotation
                - iOS version
                - Device model
            """.trimIndent()
        ) {
            if (!iosAutomationClient.isServerRunning()) {
                return@tool "iOS automation server is not running. Use 'ios_start_automation_server' first."
            }
            iosAutomationClient.getDeviceInfo()
        }
    }

    private fun registerPressHome(scope: ToolScope) {
        scope.tool(
            name = "ios_press_home",
            description = """
                Press the home button on the iOS simulator.
                The iOS automation server must be running first (use ios_start_automation_server).

                Returns to the home screen. The current app moves to the background.
            """.trimIndent()
        ) {
            if (!iosAutomationClient.isServerRunning()) {
                return@tool "iOS automation server is not running. Use 'ios_start_automation_server' first."
            }
            iosAutomationClient.pressHome()
        }
    }

    private fun registerInputText(scope: ToolScope) {
        scope.tool(
            name = "ios_input_text",
            description = """
                Types text into the currently focused element on the iOS simulator.
                The iOS automation server must be running first (use ios_start_automation_server).

                WORKFLOW: First tap on a text field using 'ios_tap_by_coordinates' to focus it,
                then call this tool to type text into it.

                PARAMETERS:
                - text (required): The text to type.
                - bundleId (required for app UI): Bundle ID of the target app (e.g., "com.apple.Preferences").
                  ALWAYS provide bundleId when typing into a third-party or system app.
                  Without bundleId, the server targets Springboard and will fail if no focused element is found.
                  Only omit bundleId when interacting with Springboard system UI itself.
            """.trimIndent(),
            inputSchema = Tool.Input(required = listOf("text"))
        ) { request ->
            if (!iosAutomationClient.isServerRunning()) {
                return@tool "iOS automation server is not running. Use 'ios_start_automation_server' first."
            }
            val text = request.requireString("text")
            val bundleId = request.optionalString("bundleId")
            iosAutomationClient.inputText(text, bundleId)
        }
    }

    private fun registerStopAutomationServer(scope: ToolScope) {
        scope.tool(
            name = "ios_stop_automation_server",
            description = "Stops the iOS automation server running on the simulator."
        ) {
            val process = iosXcodebuildProcess
            if (process != null && process.isAlive) {
                process.destroyForcibly()
                iosXcodebuildProcess = null
                "iOS automation server stopped successfully."
            } else {
                iosXcodebuildProcess = null
                "iOS automation server is not running."
            }
        }
    }
}
