package com.example.visiontest.tools

import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.utils.ErrorHandler.PACKAGE_NAME_REQUIRED
import io.modelcontextprotocol.kotlin.sdk.Tool

class AndroidDeviceToolRegistrar(
    private val android: DeviceConfig
) : ToolRegistrar {

    companion object {
        private const val PROP_MODEL = "ro.product.model"
        private const val PROP_ANDROID_VERSION = "ro.build.version.release"
        private const val PROP_SDK_VERSION = "ro.build.version.sdk"
    }

    override fun registerTools(scope: ToolScope) {
        registerAvailableDevice(scope)
        registerListApps(scope)
        registerInfoApp(scope)
        registerLaunchApp(scope)
    }

    private fun registerAvailableDevice(scope: ToolScope) {
        scope.tool(
            name = "available_device_android",
            description = "Returns detailed information about the first available Android device, including model, Android version, SDK version, and device state. Automatically selects the first active device connected via ADB."
        ) {
            val result = android.getFirstAvailableDevice()
            val deviceProps = android.executeShell("getprop", result.id)
            val modelName = ToolHelpers.extractProperty(deviceProps, PROP_MODEL)
            val androidVersion = ToolHelpers.extractProperty(deviceProps, PROP_ANDROID_VERSION)
            val sdkVersion = ToolHelpers.extractProperty(deviceProps, PROP_SDK_VERSION)

            """
            |Device found:
            |Serial: ${result.id}
            |Model: $modelName
            |Android Version: $androidVersion
            |SDK Version: $sdkVersion
            |State: ${result.state}
            """.trimMargin()
        }
    }

    private fun registerListApps(scope: ToolScope) {
        scope.tool(
            name = "list_apps_android",
            description = "Returns a complete list of all applications installed on the Android device. Returns package names (e.g., com.example.app) for all installed apps."
        ) {
            val result = android.listApps()
            if (result.isEmpty()) {
                "No apps found on the device"
            } else {
                "Found these apps: ${result.joinToString(", ")}"
            }
        }
    }

    private fun registerInfoApp(scope: ToolScope) {
        scope.tool(
            name = "info_app_android",
            description = "Returns detailed information about a specific Android application. Requires 'packageName' parameter (e.g., com.example.app). Returns version info, SDK requirements, installation dates, and permissions.",
            inputSchema = Tool.Input(required = listOf("packageName"))
        ) { request ->
            val packageName = request.requireString("packageName")
            val rawResult = android.getAppInfo(packageName)
            ToolHelpers.formatAppInfo(rawResult, packageName)
        }
    }

    private fun registerLaunchApp(scope: ToolScope) {
        scope.tool(
            name = "launch_app_android",
            description = "Launches an Android application on the connected device. Requires 'packageName' parameter (e.g., com.example.app). Uses monkey command to launch the app's main activity.",
            inputSchema = Tool.Input(required = listOf("packageName"))
        ) { request ->
            val packageName = request.requireString("packageName")
            val result = android.launchApp(packageName)
            if (result) {
                "Successfully launched the app: $packageName"
            } else {
                "Failed to launch the app: $packageName"
            }
        }
    }
}
