package com.example.visiontest.config

/**
 * Configuration constants for the Automation Server integration.
 *
 * These constants define the communication parameters between the MCP server
 * and the Automation Server running on Android devices.
 *
 * Note: These values must match the corresponding values in the automation-server
 * Android module's ServerConfig.kt to ensure compatibility.
 */
object AutomationConfig {
    /**
     * Default port for the JSON-RPC automation server.
     * Must match [com.example.automationserver.config.ServerConfig.DEFAULT_PORT]
     */
    const val DEFAULT_PORT = 9008

    /**
     * Minimum allowed port number (non-privileged ports only).
     */
    const val MIN_PORT = 1024

    /**
     * Maximum allowed port number.
     */
    const val MAX_PORT = 65535

    /**
     * Default host for connecting to the automation server.
     * Uses localhost because ADB port forwarding maps device port to host.
     */
    const val DEFAULT_HOST = "localhost"

    /**
     * Package name of the automation server Android app.
     */
    const val AUTOMATION_SERVER_PACKAGE = "com.example.automationserver"

    /**
     * Test package name (instrumentation APK).
     */
    const val AUTOMATION_SERVER_TEST_PACKAGE = "com.example.automationserver.test"

    /**
     * Instrumentation runner class name.
     */
    const val INSTRUMENTATION_RUNNER = "com.example.automationserver.AutomationInstrumentationRunner"

    /**
     * Test class and method for starting the automation server.
     */
    const val AUTOMATION_SERVER_TEST_CLASS = "com.example.automationserver.AutomationServerTest#runAutomationServer"
}
