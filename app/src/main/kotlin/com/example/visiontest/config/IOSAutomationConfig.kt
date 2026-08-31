package com.example.visiontest.config

/**
 * Configuration constants for the iOS Automation Server integration.
 *
 * These constants define the communication parameters between the MCP server
 * and the iOS Automation Server running on the iOS simulator via XCUITest.
 *
 * Note: Unlike Android, iOS simulators share the Mac's network stack,
 * so no port forwarding is needed.
 */
object IOSAutomationConfig {
    /**
     * Default port for the iOS JSON-RPC automation server.
     * Different from Android's 9008 to avoid conflicts.
     */
    const val DEFAULT_PORT = 9009

    /**
     * Minimum allowed port number (non-privileged ports only).
     */
    const val MIN_PORT = 1024

    /**
     * Maximum allowed port number.
     */
    const val MAX_PORT = 65535

    /**
     * Default host for connecting to the iOS automation server.
     * iOS simulators share the Mac's network stack, so localhost works directly.
     */
    const val DEFAULT_HOST = "localhost"

    /**
     * Relative path to the Xcode project from the project root.
     */
    const val XCODE_PROJECT_PATH = "ios-automation-server/IOSAutomationServer.xcodeproj"

    /**
     * Environment variable to override the Xcode project path.
     */
    const val XCODE_PROJECT_PATH_ENV = "VISION_TEST_IOS_PROJECT_PATH"

    /**
     * UI Test target name in the Xcode project.
     */
    const val UI_TEST_TARGET = "IOSAutomationServerUITests"

    /**
     * Test class name for starting the automation server.
     */
    const val TEST_CLASS = "AutomationServerUITest"

    /**
     * Test method name for starting the automation server.
     */
    const val TEST_METHOD = "testRunAutomationServer"

    /**
     * Scheme name in the Xcode project.
     */
    const val XCODE_SCHEME = "IOSAutomationServer"

    /**
     * Subdirectory name for the pre-built iOS test bundle within the install directory.
     */
    const val XCTESTRUN_BUNDLE_DIR = "ios-automation-server"

    /**
     * Interval between findElement polls in ios_wait_for_element / ios_wait_until_gone.
     */
    const val WAIT_POLL_INTERVAL_MS = 500L

    /**
     * Overall wait budget when the caller does not provide timeoutMs.
     */
    const val WAIT_DEFAULT_TIMEOUT_MS = 10_000L

    /**
     * Upper bound for a caller-provided wait timeout.
     */
    const val WAIT_MAX_TIMEOUT_MS = 30_000L

    /**
     * Tool DSL timeout for wait tools. Must exceed [WAIT_MAX_TIMEOUT_MS] so the
     * wait's own deadline fires first and produces the structured timeout message.
     */
    const val WAIT_TOOL_TIMEOUT_MS = 35_000L
}
