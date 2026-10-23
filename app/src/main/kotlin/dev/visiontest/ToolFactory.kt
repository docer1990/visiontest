package dev.visiontest

import dev.visiontest.performance.TraceRecorder
import dev.visiontest.android.AutomationClient
import dev.visiontest.common.DeviceConfig
import dev.visiontest.discovery.ToolDiscovery
import dev.visiontest.ios.IOSAutomationClient
import dev.visiontest.tools.AndroidAutomationToolRegistrar
import dev.visiontest.tools.AndroidDeviceToolRegistrar
import dev.visiontest.tools.AndroidStopToolRegistrar
import dev.visiontest.tools.AndroidWaitToolRegistrar
import dev.visiontest.tools.IOSAutomationToolRegistrar
import dev.visiontest.tools.IOSDeviceToolRegistrar
import dev.visiontest.tools.IOSWaitToolRegistrar
import dev.visiontest.tools.ToolRegistrar
import dev.visiontest.tools.ToolScope
import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.slf4j.Logger

class ToolFactory internal constructor(
    private val android: DeviceConfig,
    private val ios: DeviceConfig,
    private val logger: Logger,
    recorder: TraceRecorder,
    private val toolTimeoutMillis: Long = 10000L,
    clients: Pair<AutomationClient, IOSAutomationClient> =
        AutomationClient(trace = recorder) to IOSAutomationClient(trace = recorder)
) {
    constructor(
        android: DeviceConfig,
        ios: DeviceConfig,
        logger: Logger,
        toolTimeoutMillis: Long = 10000L,
        automationClient: AutomationClient = AutomationClient(),
        iosAutomationClient: IOSAutomationClient = IOSAutomationClient(),
    ) : this(android, ios, logger, TraceRecorder.Disabled, toolTimeoutMillis, automationClient to iosAutomationClient)

    private val automationClient = clients.first
    private val iosAutomationClient = clients.second
    private val discovery = ToolDiscovery(logger)

    private val registrars: List<ToolRegistrar> = listOf(
        AndroidDeviceToolRegistrar(android, recorder),
        AndroidAutomationToolRegistrar(android, automationClient, discovery, recorder),
        AndroidStopToolRegistrar(android, automationClient, recorder),
        AndroidWaitToolRegistrar(automationClient, recorder),
        IOSDeviceToolRegistrar(ios, recorder),
        IOSAutomationToolRegistrar(ios, iosAutomationClient, discovery, logger, recorder),
        IOSWaitToolRegistrar(iosAutomationClient, recorder)
    )

    fun registerAllTools(server: Server) = registerAllTools(server, TraceRecorder.Disabled)

    internal fun registerAllTools(server: Server, recorder: TraceRecorder) {
        val scope = ToolScope(server, logger, toolTimeoutMillis, recorder)
        registrars.forEach { it.registerTools(scope) }
    }
}
