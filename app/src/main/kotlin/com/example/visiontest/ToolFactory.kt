package com.example.visiontest

import com.example.visiontest.utils.ErrorHandler
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.Logger
import com.example.visiontest.utils.ErrorHandler.DEVICE_NOT_FOUND
import com.example.visiontest.utils.ErrorHandler.PACKAGE_NAME_REQUIRED



class ToolFactory(
    private val android: AndroidConfig,
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
        registerAvailableDeviceTool(server)
        registerListAppsTool(server)
        registerInfoAppTool(server)
        registerLaunchAppTool(server)
    }

    private fun registerAvailableDeviceTool(server: Server) {
        server.addTool(
            name = "available_device",
            description = "Returns detailed information about the first available device",
            inputSchema = Tool.Input()
        ) {
            try {
                val result = runWithTimeout {
                    android.getFirstAvailableDevice()
                }
                // Fetch additional device information
                val deviceProps = runWithTimeout {
                    android.executeShellOnDevice(result.serial, "getprop")
                }
                val modelName = extractProperty(deviceProps, PROP_MODEL)
                val androidVersion = extractProperty(deviceProps, PROP_ANDROID_VERSION)
                val sdkVersion = extractProperty(deviceProps, PROP_SDK_VERSION)

                val deviceInfo = """
                |Device found:
                |Serial: ${result.serial}
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

    private fun registerListAppsTool(server: Server) {
        server.addTool(
            name = "list_apps",
            description = "Returns a list of apps installed on the device",
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

    private fun registerInfoAppTool(server: Server) {
        server.addTool(
            name = "info_app",
            description = "Returns formatted information about the app",
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

    private fun registerLaunchAppTool(server: Server) {
        server.addTool(
            name = "launch_app",
            description = "Launches the app",
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

}