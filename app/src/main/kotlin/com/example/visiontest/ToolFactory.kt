package com.example.visiontest

import com.example.visiontest.android.Android
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



class ToolFactory(
    private val android: DeviceConfig,
    private val ios: DeviceConfig,
    private val logger: Logger,
    private val toolTimeoutMillis: Long = 10000L
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

}