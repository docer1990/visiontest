package com.example.visiontest.tools

import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.config.IOSAutomationConfig
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.ios.IOSAutomationClient
import com.google.gson.JsonParser
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

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
        registerScreenshot(scope)
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

            val includeDisabled = request.optionalBoolean("includeDisabled") ?: false
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

            val direction = request.requireDirection()

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

    private fun registerScreenshot(scope: ToolScope) {
        scope.tool(
            name = "ios_screenshot",
            description = """
                Captures a screenshot of the current iOS simulator display and saves it as a PNG file on the host.
                The iOS automation server must be running first (use ios_start_automation_server).

                OPTIONAL PARAMETERS:
                - outputPath: Absolute or relative path where the PNG will be written.
                  Relative paths resolve against the MCP server's working directory (typically the
                  user's current project). If the file already exists it will be overwritten.
                  Missing parent directories are created automatically.
                  If omitted, saves to ./screenshots/ios_screenshot_<yyyyMMdd_HHmmss>.png relative to
                  the server's working directory (i.e. the current project, not the visiontest install dir).

                Returns the absolute path of the saved PNG.
            """.trimIndent(),
            timeoutMs = 30000
        ) { request ->
            captureScreenshot(request.optionalString("outputPath"))
        }
    }

    internal suspend fun captureScreenshot(outputPath: String?): String {
        if (!iosAutomationClient.isServerRunning()) {
            return "iOS automation server is not running. Use 'ios_start_automation_server' first."
        }

        val response = iosAutomationClient.screenshot()
        val root = try {
            JsonParser.parseString(response).asJsonObject
        } catch (e: Exception) {
            return "Screenshot failed: unable to parse response from iOS automation server (${e.message})."
        }

        // JSON-RPC 2.0 envelope: either `result` OR `error` is present at the top level.
        // Check `error` first so we can surface the server's message and map `methodNotFound`
        // to the outdated-bundle guidance (older bundles won't know about `ui.screenshot`).
        val errorElement = root.get("error")
        if (errorElement != null && !errorElement.isJsonNull) {
            if (errorElement.isJsonObject) {
                val errorObj = errorElement.asJsonObject
                val code = errorObj.get("code")?.asInt
                val message = errorObj.get("message")?.asString ?: "unknown error"
                if (code == JSON_RPC_METHOD_NOT_FOUND) {
                    return "Screenshot failed: the iOS automation server does not recognize 'ui.screenshot' " +
                        "(JSON-RPC methodNotFound). This indicates an outdated iOS automation server bundle " +
                        "— rebuild from source or update the installed bundle."
                }
                return "Screenshot failed: iOS automation server returned error ($code): $message"
            }
            return "Screenshot failed: iOS automation server returned a malformed error envelope."
        }

        val resultElement = root.get("result")
        if (resultElement == null || resultElement.isJsonNull) {
            return "Screenshot failed: response missing 'result' object."
        }
        if (!resultElement.isJsonObject) {
            return "Screenshot failed: response 'result' is not a JSON object."
        }
        val result = resultElement.asJsonObject

        val success = result.get("success")?.asBoolean ?: false
        if (!success) {
            val error = result.get("error")?.asString ?: "unknown error"
            return "Screenshot failed on the iOS automation server: $error"
        }

        val pngBase64 = result.get("pngBase64")?.asString
        if (pngBase64.isNullOrEmpty()) {
            return "Screenshot failed: response missing 'pngBase64'. This may indicate an outdated iOS automation server bundle — rebuild from source or update the installed bundle."
        }

        val targetFile = resolveScreenshotPath(outputPath)
        return writeScreenshot(targetFile, pngBase64)
    }

    internal fun resolveScreenshotPath(outputPath: String?): File {
        if (outputPath != null && outputPath.isNotBlank()) {
            return File(outputPath).absoluteFile
        }
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        // Default to the MCP server's working directory so screenshots land in the
        // user's current project (not the visiontest install dir). Coding agents like
        // Claude Code launch the server with CWD set to the project they're working on.
        return File("screenshots/ios_screenshot_$timestamp.png").absoluteFile
    }

    /**
     * Decodes the base64 PNG and writes it atomically to [target].
     * Runs on Dispatchers.IO so we don't block the tool handler's coroutine context.
     * Writes to a sibling temp file first, then moves into place so a failure or cancellation
     * mid-write cannot leave a partial PNG at [target].
     *
     * Returns a user-facing result string (success or a specific error message).
     */
    internal suspend fun writeScreenshot(target: File, pngBase64: String): String = withContext(Dispatchers.IO) {
        val bytes = try {
            Base64.getDecoder().decode(pngBase64)
        } catch (e: IllegalArgumentException) {
            return@withContext "Screenshot failed: iOS automation server returned invalid base64 PNG data (${e.message})."
        }

        val targetPath = target.toPath()
        val parentDir = target.parentFile
            ?: return@withContext "Screenshot failed: cannot determine parent directory for ${target.absolutePath}."

        try {
            Files.createDirectories(parentDir.toPath())
        } catch (e: IOException) {
            return@withContext "Screenshot failed: unable to create parent directory ${parentDir.absolutePath} (${e.message})."
        }

        val tempFile = try {
            Files.createTempFile(parentDir.toPath(), ".ios_screenshot_", ".png.tmp")
        } catch (e: IOException) {
            return@withContext "Screenshot failed: unable to create temp file in ${parentDir.absolutePath} (${e.message})."
        }

        try {
            Files.write(tempFile, bytes)
            // ATOMIC_MOVE isn't guaranteed across filesystems, but tempFile is a sibling of
            // target so they're on the same FS. Fall back to plain replace on rare failures.
            try {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
            "Screenshot saved to ${target.absolutePath}"
        } catch (e: IOException) {
            Files.deleteIfExists(tempFile)
            "Screenshot failed: unable to write PNG to ${target.absolutePath} (${e.message})."
        } catch (e: Exception) {
            Files.deleteIfExists(tempFile)
            throw e
        }
    }

    companion object {
        /** Standard JSON-RPC 2.0 error code for an unknown method. */
        private const val JSON_RPC_METHOD_NOT_FOUND = -32601
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
