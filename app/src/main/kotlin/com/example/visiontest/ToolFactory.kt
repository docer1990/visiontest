package com.example.visiontest

import com.example.visiontest.performance.TraceRecorder
import com.example.visiontest.android.AutomationClient
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.tools.*
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
