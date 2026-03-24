package com.example.visiontest.tools

import com.example.visiontest.common.DeviceConfig
import io.modelcontextprotocol.kotlin.sdk.Tool

class IOSDeviceToolRegistrar(
    private val ios: DeviceConfig
) : ToolRegistrar {

    override fun registerTools(scope: ToolScope) {
        registerAvailableDevice(scope)
        registerListApps(scope)
        registerInfoApp(scope)
        registerLaunchApp(scope)
    }

    private fun registerAvailableDevice(scope: ToolScope) {
        scope.tool(
            name = "ios_available_device",
            description = "Returns detailed information about the first available iOS device or simulator. Includes device ID, name, type, state (Booted/Shutdown), iOS version, and model. Prioritizes booted simulators over shutdown ones."
        ) {
            val device = ios.getFirstAvailableDevice()

            """
            |iOS Device found:
            |ID: ${device.id}
            |Name: ${device.name}
            |Type: ${device.type}
            |State: ${device.state}
            |OS Version: ${device.osVersion ?: "Unknown"}
            |Model: ${device.modelName ?: "Unknown"}
            """.trimMargin()
        }
    }

    private fun registerListApps(scope: ToolScope) {
        scope.tool(
            name = "ios_list_apps",
            description = "Returns a complete list of all applications installed on the iOS device or simulator. Returns bundle IDs (e.g., com.apple.mobilesafari) for all installed apps. Device must be booted."
        ) {
            val result = ios.listApps()
            if (result.isEmpty()) {
                "No apps found on the iOS device"
            } else {
                "Found these apps: ${result.joinToString(", ")}"
            }
        }
    }

    private fun registerInfoApp(scope: ToolScope) {
        scope.tool(
            name = "ios_info_app",
            description = "Returns detailed information about a specific iOS application. Requires 'bundleId' parameter (e.g., com.apple.mobilesafari). Returns bundle ID and app container path. Device must be booted.",
            inputSchema = Tool.Input(required = listOf("bundleId"))
        ) { request ->
            val bundleId = request.requireString("bundleId")
            val rawResult = ios.getAppInfo(bundleId)
            "App Information for $bundleId:\n$rawResult"
        }
    }

    private fun registerLaunchApp(scope: ToolScope) {
        scope.tool(
            name = "ios_launch_app",
            description = "Launches an iOS application on the device or simulator. Requires 'bundleId' parameter (e.g., com.apple.mobilesafari). Device must be booted before launching apps.",
            inputSchema = Tool.Input(required = listOf("bundleId"))
        ) { request ->
            val bundleId = request.requireString("bundleId")
            val result = ios.launchApp(bundleId)
            if (result) {
                "Successfully launched the iOS app: $bundleId"
            } else {
                "Failed to launch the iOS app: $bundleId"
            }
        }
    }
}
