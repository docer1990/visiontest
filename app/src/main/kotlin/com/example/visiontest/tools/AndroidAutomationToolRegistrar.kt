package com.example.visiontest.tools

import com.example.visiontest.android.Android
import com.example.visiontest.android.AutomationClient
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.config.AutomationConfig
import com.example.visiontest.discovery.ToolDiscovery
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AndroidAutomationToolRegistrar(
    private val android: DeviceConfig,
    private val automationClient: AutomationClient,
    private val discovery: ToolDiscovery
) : ToolRegistrar {

    override fun registerTools(scope: ToolScope) {
        registerInstallAutomationServer(scope)
        registerStartAutomationServer(scope)
        registerAutomationServerStatus(scope)
        registerGetUiHierarchy(scope)
        registerFindElement(scope)
        registerTapByCoordinates(scope)
        registerSwipe(scope)
        registerSwipeDirection(scope)
        registerSwipeOnElement(scope)
        registerPressBack(scope)
        registerPressHome(scope)
        registerInputText(scope)
        registerGetDeviceInfo(scope)
        registerGetInteractiveElements(scope)
    }

    private fun registerInstallAutomationServer(scope: ToolScope) {
        scope.tool(
            name = "install_automation_server",
            description = "Installs the automation server APKs on the connected Android device. Run this once before using start_automation_server."
        ) {
            val device = android.getFirstAvailableDevice()

            val apkPath = discovery.findAutomationServerApk()
                ?: return@tool "Automation server APK not found. Re-run install.sh to download APKs, or set VISION_TEST_APK_PATH environment variable. To build from source: ./gradlew :automation-server:assembleDebug :automation-server:assembleDebugAndroidTest"

            val androidDevice = android as? Android
                ?: return@tool "Android device configuration not available"

            val resolvedMainApk = discovery.resolveMainApkPath(apkPath)
            if (resolvedMainApk != null) {
                androidDevice.executeAdb("install", "-r", resolvedMainApk)
            } else {
                return@tool "Main APK not found at the expected path derived from test APK: $apkPath. Ensure the main automation-server APK is built/installed (e.g., via :automation-server:assembleDebug), or re-run install.sh or set VISION_TEST_APK_PATH."
            }

            androidDevice.executeAdb("install", "-r", apkPath)

            "Automation server APKs installed successfully on device ${device.id}. Use 'start_automation_server' to start the server."
        }
    }

    private fun registerStartAutomationServer(scope: ToolScope) {
        scope.tool(
            name = "start_automation_server",
            description = "Starts the automation server on the connected Android device. The APKs must be installed first using install_automation_server. Sets up port forwarding and starts the instrumentation server.",
            timeoutMs = 30000
        ) {
            val device = android.getFirstAvailableDevice()
            val androidDevice = android as? Android
                ?: return@tool "Android device configuration not available"

            val port = AutomationConfig.DEFAULT_PORT

            if (automationClient.isServerRunning()) {
                return@tool "Automation server is already running on localhost:$port"
            }

            androidDevice.executeAdb("forward", "tcp:$port", "tcp:$port")

            withContext(Dispatchers.IO) {
                val command = listOf(
                    "adb", "-s", device.id, "shell",
                    "am", "instrument", "-w",
                    "-e", "port", port.toString(),
                    "-e", "class", AutomationConfig.AUTOMATION_SERVER_TEST_CLASS,
                    "${AutomationConfig.AUTOMATION_SERVER_TEST_PACKAGE}/${AutomationConfig.INSTRUMENTATION_RUNNER}"
                )
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
            }

            var attempts = 0
            val maxAttempts = 10
            while (attempts < maxAttempts) {
                delay(500)
                if (automationClient.isServerRunning()) {
                    return@tool "Automation server started successfully on device ${device.id}. Server is listening on localhost:$port"
                }
                attempts++
            }

            "Automation server may not have started properly. Check device logs with: adb logcat | grep AutomationServer"
        }
    }

    private fun registerAutomationServerStatus(scope: ToolScope) {
        scope.tool(
            name = "automation_server_status",
            description = "Checks if the automation server is running on the connected Android device. Returns server status and connection information."
        ) {
            val isRunning = automationClient.isServerRunning()
            if (isRunning) {
                "Automation server is running and accessible at localhost:${AutomationConfig.DEFAULT_PORT}"
            } else {
                "Automation server is not running. Use 'start_automation_server' to start it."
            }
        }
    }

    private fun registerGetUiHierarchy(scope: ToolScope) {
        scope.tool(
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
            timeoutMs = 30000
        ) {
            if (!automationClient.isServerRunning()) {
                return@tool "Automation server is not running. Use 'start_automation_server' first."
            }
            automationClient.getUiHierarchy()
        }
    }

    private fun registerFindElement(scope: ToolScope) {
        scope.tool(
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
            timeoutMs = 30000
        ) { request ->
            if (!automationClient.isServerRunning()) {
                return@tool "Automation server is not running. Use 'start_automation_server' first."
            }

            val text = request.optionalString("text")
            val textContains = request.optionalString("textContains")
            val resourceId = request.optionalString("resourceId")
            val className = request.optionalString("className")
            val contentDescription = request.optionalString("contentDescription")

            if (text == null && textContains == null && resourceId == null &&
                className == null && contentDescription == null) {
                return@tool "Error: At least one selector required (text, textContains, resourceId, className, or contentDescription)"
            }

            automationClient.findElement(
                text = text,
                textContains = textContains,
                resourceId = resourceId,
                className = className,
                contentDescription = contentDescription
            )
        }
    }

    private fun registerTapByCoordinates(scope: ToolScope) {
        scope.tool(
            name = "android_tap_by_coordinates",
            description = """
                Tap on the Android device screen at the specified (x, y) coordinates.
                The automation server must be running first (use start_automation_server).

                WORKFLOW: Prefer calling 'get_interactive_elements' first to locate tappable elements.
                Each interactive element includes ready-to-use 'centerX' and 'centerY' fields that you can pass
                directly as the 'x' and 'y' parameters to this tool.
                If you instead use 'get_ui_hierarchy' or 'find_element', elements expose bounds in the format
                [left,top][right,bottom] (e.g., [100,200][300,400]). In that case, manually calculate center
                coordinates: x = (left + right) / 2, y = (top + bottom) / 2.
                Example (manual calculation): For bounds [100,200][300,400], tap at x=200, y=300.

                USE CASES:
                - Tap buttons, links, or interactive elements after locating them
                - Tap at specific screen positions for gestures or navigation

                TIP: If you know the element's text or resourceId, use 'find_element' first
                to get precise bounds rather than guessing coordinates.
                """.trimIndent(),
            inputSchema = Tool.Input(required = listOf("x", "y"))
        ) { request ->
            if (!automationClient.isServerRunning()) {
                return@tool "Automation server is not running. Use 'start_automation_server' first."
            }
            val x = request.requireInt("x")
            val y = request.requireInt("y")
            automationClient.tapByCoordinates(x, y)
        }
    }

    private fun registerSwipe(scope: ToolScope) {
        scope.tool(
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
            inputSchema = Tool.Input(required = listOf("startX", "startY", "endX", "endY"))
        ) { request ->
            if (!automationClient.isServerRunning()) {
                return@tool "Automation server is not running. Use 'start_automation_server' first."
            }
            val startX = request.requireInt("startX")
            val startY = request.requireInt("startY")
            val endX = request.requireInt("endX")
            val endY = request.requireInt("endY")
            val steps = request.optionalInt("steps") ?: 20
            automationClient.swipe(startX, startY, endX, endY, steps)
        }
    }

    private fun registerSwipeDirection(scope: ToolScope) {
        scope.tool(
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
            inputSchema = Tool.Input(required = listOf("direction"))
        ) { request ->
            if (!automationClient.isServerRunning()) {
                return@tool "Automation server is not running. Use 'start_automation_server' first."
            }

            val direction = request.requireString("direction")
            val validDirections = listOf("up", "down", "left", "right")
            if (direction.lowercase() !in validDirections) {
                return@tool "Error: Invalid direction '$direction'. Must be one of: ${validDirections.joinToString()}"
            }

            val distance = request.optionalString("distance") ?: "medium"
            val speed = request.optionalString("speed") ?: "normal"

            automationClient.swipeByDirection(direction, distance, speed)
        }
    }

    private fun registerSwipeOnElement(scope: ToolScope) {
        scope.tool(
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
            inputSchema = Tool.Input(required = listOf("direction"))
        ) { request ->
            if (!automationClient.isServerRunning()) {
                return@tool "Automation server is not running. Use 'start_automation_server' first."
            }

            val direction = request.requireString("direction")
            val validDirections = listOf("up", "down", "left", "right")
            if (direction.lowercase() !in validDirections) {
                return@tool "Error: Invalid direction '$direction'. Must be one of: ${validDirections.joinToString()}"
            }

            val text = request.optionalString("text")
            val textContains = request.optionalString("textContains")
            val resourceId = request.optionalString("resourceId")
            val className = request.optionalString("className")
            val contentDescription = request.optionalString("contentDescription")

            if (text == null && textContains == null && resourceId == null &&
                className == null && contentDescription == null) {
                return@tool "Error: At least one selector required (text, textContains, resourceId, className, or contentDescription)"
            }

            val speed = request.optionalString("speed") ?: "normal"

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
    }

    private fun registerPressBack(scope: ToolScope) {
        scope.tool(
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
            """.trimIndent()
        ) {
            if (!automationClient.isServerRunning()) {
                return@tool "Automation server is not running. Use 'start_automation_server' first."
            }
            automationClient.pressBack()
        }
    }

    private fun registerPressHome(scope: ToolScope) {
        scope.tool(
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
            """.trimIndent()
        ) {
            if (!automationClient.isServerRunning()) {
                return@tool "Automation server is not running. Use 'start_automation_server' first."
            }
            automationClient.pressHome()
        }
    }

    private fun registerInputText(scope: ToolScope) {
        scope.tool(
            name = "android_input_text",
            description = """
                Types text into the currently focused element on the Android device.
                The automation server must be running first (use start_automation_server).

                WORKFLOW: First tap on a text field using 'android_tap_by_coordinates' to focus it,
                then call this tool to type text into it.
            """.trimIndent(),
            inputSchema = Tool.Input(required = listOf("text"))
        ) { request ->
            if (!automationClient.isServerRunning()) {
                return@tool "Automation server is not running. Use 'start_automation_server' first."
            }
            val text = request.requireString("text")
            automationClient.inputText(text)
        }
    }

    private fun registerGetDeviceInfo(scope: ToolScope) {
        scope.tool(
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
            """.trimIndent()
        ) {
            if (!automationClient.isServerRunning()) {
                return@tool "Automation server is not running. Use 'start_automation_server' first."
            }
            automationClient.getDeviceInfo()
        }
    }

    private fun registerGetInteractiveElements(scope: ToolScope) {
        scope.tool(
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
            timeoutMs = 30000
        ) { request ->
            if (!automationClient.isServerRunning()) {
                return@tool "Automation server is not running. Use 'start_automation_server' first."
            }

            val includeDisabledRaw = request.optionalString("includeDisabled")
            val includeDisabled = when (includeDisabledRaw) {
                null -> false
                "true" -> true
                "false" -> false
                else -> return@tool "Invalid value for 'includeDisabled': '$includeDisabledRaw'. Must be true or false."
            }

            automationClient.getInteractiveElements(includeDisabled)
        }
    }
}
