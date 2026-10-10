package com.example.visiontest.tools

import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.performance.TraceRecorder
import com.example.visiontest.performance.TraceStage
import com.example.visiontest.performance.OperationOutcome
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
class AndroidDeviceToolRegistrar internal constructor(
    private val android: DeviceConfig,
    private val trace: TraceRecorder,
) : ToolRegistrar {
    constructor(android: DeviceConfig) : this(android, TraceRecorder.Disabled)

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
            availableDevice()
        }
    }
    internal suspend fun availableDevice(json: Boolean = false): String = trace.span(TraceStage.OPERATION) {
        val result = android.getFirstAvailableDevice()
        val deviceProps = android.executeShell("getprop", result.id)
        val modelName = ToolHelpers.extractProperty(deviceProps, PROP_MODEL)
        val androidVersion = ToolHelpers.extractProperty(deviceProps, PROP_ANDROID_VERSION)
        val sdkVersion = ToolHelpers.extractProperty(deviceProps, PROP_SDK_VERSION)
        if (json) return@span buildJsonObject {
            put("id", result.id)
            put("name", result.name)
            put("type", result.type.name)
            put("state", result.state)
            put("modelName", modelName.takeUnless { it == "Unknown" })
            put("osVersion", androidVersion.takeUnless { it == "Unknown" })
            put("sdkVersion", sdkVersion.takeUnless { it == "Unknown" })
        }.toString()
        return@span """
            |Device found:
            |Serial: ${result.id}
            |Model: $modelName
            |Android Version: $androidVersion
            |SDK Version: $sdkVersion
            |State: ${result.state}
            """.trimMargin()
    }

    private fun registerListApps(scope: ToolScope) {
        scope.tool(
            name = "list_apps_android",
            description = "Returns a complete list of all applications installed on the Android device. Returns package names (e.g., com.example.app) for all installed apps."
        ) {
            listApps()
        }
    }
    internal suspend fun listApps(json: Boolean = false): String = trace.span(TraceStage.OPERATION) {
        val result = android.listApps()
        if (json) return@span buildJsonObject {
            put("apps", buildJsonArray { result.forEach { add(it) } })
        }.toString()
        return@span if (result.isEmpty()) {
            "No apps found on the device"
        } else {
            "Found these apps: ${result.joinToString(", ")}"
        }
    }

    private fun registerInfoApp(scope: ToolScope) {
        scope.tool(
            name = "info_app_android",
            description = "Returns detailed information about a specific Android application. Requires 'packageName' parameter (e.g., com.example.app). Returns version info, SDK requirements, installation dates, and permissions.",
            inputSchema = Tool.Input(required = listOf("packageName"))
        ) { request ->
            val packageName = request.requireString("packageName")
            infoApp(packageName)
        }
    }
    internal suspend fun infoApp(packageName: String, json: Boolean = false): String =
        trace.span(TraceStage.OPERATION) {
            val rawResult = android.getAppInfo(packageName)
            if (json) return@span buildJsonObject {
                put("id", packageName)
                put("rawInfo", rawResult)
            }.toString()
            return@span ToolHelpers.formatAppInfo(rawResult, packageName)
        }

    private fun registerLaunchApp(scope: ToolScope) {
        scope.tool(
            name = "launch_app_android",
            description = "Launches an Android application on the connected device. Requires 'packageName' parameter (e.g., com.example.app). Uses monkey command to launch the app's main activity.",
            inputSchema = Tool.Input(required = listOf("packageName"))
        ) { request ->
            val packageName = request.requireString("packageName")
            launchApp(packageName)
        }
    }
    internal suspend fun launchApp(packageName: String): String = trace.span(TraceStage.OPERATION) {
        val result = android.launchApp(packageName)
        trace.operationOutcome(if (result) OperationOutcome.SUCCESS else OperationOutcome.FAILURE)
        return@span if (result) {
            "Successfully launched the app: $packageName"
        } else {
            "Failed to launch the app: $packageName"
        }
    }
}
