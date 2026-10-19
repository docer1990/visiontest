package com.example.visiontest.cli

import com.example.visiontest.android.AutomationClient
import com.example.visiontest.cli.commands.FlowCommand
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.tools.AndroidAutomationToolRegistrar
import com.example.visiontest.tools.AndroidDeviceToolRegistrar
import com.example.visiontest.tools.IOSAutomationToolRegistrar
import com.example.visiontest.tools.IOSDeviceToolRegistrar
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal class FlowObservationFixture(val platform: Platform) : AutoCloseable {
    val server = MockWebServer()
    val calls = mutableListOf<String>()
    var respond: (String) -> MockResponse = { MockResponse().setBody("""{"result":{"success":true}}""") }
    val components: Lazy<ComponentHolder>

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setBody("OK")
                val method = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    .getValue("method").jsonPrimitive.content
                synchronized(calls) { calls += method }
                return respond(method)
            }
        }
        server.start()
        val backend = mockk<DeviceConfig>()
        val logger = LoggerFactory.getLogger(javaClass)
        val discovery = ToolDiscovery(logger)
        val android = AutomationClient(server.hostName, server.port)
        val ios = IOSAutomationClient(server.hostName, server.port)
        components = lazy {
            ComponentHolder(mockk(), mockk(), android, ios, AndroidDeviceToolRegistrar(backend),
                AndroidAutomationToolRegistrar(backend, android, discovery), IOSDeviceToolRegistrar(backend),
                IOSAutomationToolRegistrar(backend, ios, discovery, logger))
        }
    }

    fun run(vararg options: String): JsonObject {
        var result: CliResult? = null
        FlowCommand(components, runner = { result = executeCliResultCommand(it) })
            .parse(listOf("-p", platform.value, "--json") + options)
        return Json.parseToJsonElement(assertNotNull(result?.stdout, result?.stderr)).jsonObject
    }

    override fun close() = server.shutdown()
}

internal class FlowEvidenceScenario {
    private var results = emptyList<JsonObject>()
    fun bindings(): Map<String, () -> Unit> = mapOf(
        "Given a failed action with requested screenshot evidence" to {},
        "When evidence collection exceeds its budget" to {
            results = Platform.entries.map { platform ->
                FlowObservationFixture(platform).use { fixture ->
                    fixture.respond = { method ->
                        if (method == "ui.screenshot") MockResponse().setBody("""{"result":{"success":true}}""")
                            .setBodyDelay(200, TimeUnit.MILLISECONDS)
                        else MockResponse().setBody("""{"result":{"success":false,"error":"blocked"}}""")
                    }
                    fixture.run("--step", "press_home", "--on-failure", "screenshot", "--evidence-timeout", "30")
                }
            }
        },
        "Then the original action error and exit code are preserved" to {
            results.forEach { result ->
                assertEquals("1", result.getValue("exitCode").toString())
                assertEquals("blocked", result.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
                assertEquals("failed", result.getValue("evidence").jsonArray.single().jsonObject
                    .getValue("status").jsonPrimitive.content)
            }
        },
    )
}
