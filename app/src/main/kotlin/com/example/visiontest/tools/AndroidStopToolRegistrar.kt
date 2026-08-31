package com.example.visiontest.tools

import com.example.visiontest.CommandExecutionException
import com.example.visiontest.android.Android
import com.example.visiontest.android.AutomationClient
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.config.AutomationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Registers `stop_automation_server` (Android): force-stops the instrumentation and
 * server app processes on the device and removes the ADB port forward that
 * `start_automation_server` set up. Idempotent — stopping a server that is not
 * running succeeds with an informational message.
 */
class AndroidStopToolRegistrar(
    private val android: DeviceConfig,
    private val automationClient: AutomationClient,
    private val executeAdb: suspend (List<String>) -> String = { args ->
        (android as? Android)?.executeAdb(*args.toTypedArray()).orEmpty()
    },
) : ToolRegistrar {

    override fun registerTools(scope: ToolScope) {
        registerStopAutomationServer(scope)
    }

    // ==================== Extracted business logic ====================

    internal suspend fun stopAutomationServer(): String {
        val wasRunning = automationClient.isServerRunning()

        android.executeShell("am force-stop ${AutomationConfig.AUTOMATION_SERVER_TEST_PACKAGE}")
        android.executeShell("am force-stop ${AutomationConfig.AUTOMATION_SERVER_PACKAGE}")
        removePortForward()

        return if (wasRunning) {
            confirmServerStopped()
        } else {
            "Automation server was not running. " +
                "Cleaned up any leftover port forwarding for tcp:${AutomationConfig.DEFAULT_PORT}."
        }
    }

    private suspend fun removePortForward() {
        val endpoint = "tcp:${AutomationConfig.DEFAULT_PORT}"
        try {
            executeAdb(listOf("forward", "--remove", endpoint))
        } catch (removalError: CommandExecutionException) {
            val forwards = try {
                executeAdb(listOf("forward", "--list"))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (verificationError: Exception) {
                removalError.addSuppressed(verificationError)
                throw removalError
            }
            if (forwards.lineSequence().any { line ->
                    line.split(Regex("\\s+")).getOrNull(1) == endpoint
                }
            ) {
                throw removalError
            }
        }
    }

    private suspend fun confirmServerStopped(): String {
        repeat(AutomationConfig.STOP_VERIFY_ATTEMPTS) {
            if (!automationClient.isServerRunning()) {
                return "Automation server stopped successfully. " +
                    "Port forwarding for tcp:${AutomationConfig.DEFAULT_PORT} removed. " +
                    "Use 'start_automation_server' to start it again."
            }
            delay(AutomationConfig.WAIT_POLL_INTERVAL_MS)
        }
        return "Automation server processes were force-stopped but the server still responds on " +
            "localhost:${AutomationConfig.DEFAULT_PORT}. Another process may be bound to the port — " +
            "check with 'adb forward --list'."
    }

    // ==================== MCP Tool Registrations ====================

    private fun registerStopAutomationServer(scope: ToolScope) {
        scope.tool(
            name = "stop_automation_server",
            description = """
                Stops the automation server on the connected Android device.
                Force-stops the instrumentation and server app processes and removes
                the ADB port forwarding for tcp:${AutomationConfig.DEFAULT_PORT}.

                Idempotent: succeeds with an informational message when the server
                is not running. Use 'start_automation_server' to start it again later.
            """.trimIndent(),
            timeoutMs = 30000
        ) {
            stopAutomationServer()
        }
    }
}
