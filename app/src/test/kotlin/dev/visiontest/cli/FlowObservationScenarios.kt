package dev.visiontest.cli

import dev.visiontest.android.AutomationClient
import dev.visiontest.cli.commands.FlowCommand
import dev.visiontest.common.DeviceConfig
import dev.visiontest.discovery.ToolDiscovery
import dev.visiontest.ios.IOSAutomationClient
import dev.visiontest.tools.AndroidAutomationToolRegistrar
import dev.visiontest.tools.AndroidDeviceToolRegistrar
import dev.visiontest.tools.IOSAutomationToolRegistrar
import dev.visiontest.tools.IOSDeviceToolRegistrar
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
    val requests = mutableListOf<JsonObject>()
    var respond: (String) -> MockResponse = { MockResponse().setBody("""{"result":{"success":true}}""") }
    val components: Lazy<ComponentHolder>

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setBody("OK")
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                synchronized(requests) { requests += body }
                val method = body.getValue("method").jsonPrimitive.content
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
        val result = runResult("--json", *options)
        return Json.parseToJsonElement(assertNotNull(result.stdout, result.stderr)).jsonObject
    }

    fun runResult(vararg options: String): CliResult {
        var result: CliResult? = null
        FlowCommand(components, runner = { result = executeCliResultCommand(it) })
            .parse(listOf("-p", platform.value) + options)
        return assertNotNull(result)
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

internal class FlowFinalStateScenario {
    private var results = emptyList<JsonObject>()
    fun bindings(): Map<String, () -> Unit> = mapOf(
        "Given successful actions followed by an explicit element wait" to {},
        "When the caller requests final interactive elements" to {
            results = Platform.entries.map { platform ->
                FlowObservationFixture(platform).use { fixture ->
                    fixture.respond = { method -> MockResponse().setBody(when (method) {
                        "ui.findElement" -> """{"result":{"found":true,"resourceId":"done"}}"""
                        "ui.getInteractiveElements" -> """
                            {"result":{"success":true,"count":1,"elements":[{"resourceId":"done"}]}}
                        """.trimIndent()
                        else -> """{"result":{"success":true}}"""
                    }) }
                    fixture.run("--step", "press_home", "--step", "wait_for_element --resource-id done",
                        "--observe", "interactive-elements")
                }
            }
        },
        "Then the flow returns the elements with the completed action results" to {
            results.forEach { result ->
                assertEquals("true", result.getValue("success").toString())
                assertEquals(listOf("succeeded", "succeeded"), result.getValue("steps").jsonArray.map {
                    it.jsonObject.getValue("status").jsonPrimitive.content
                })
                val observation = result.getValue("observations").jsonArray.single().jsonObject
                assertEquals("succeeded", observation.getValue("status").jsonPrimitive.content)
                assertEquals("done", observation.getValue("result").jsonObject.getValue("elements").jsonArray
                    .single().jsonObject.getValue("resourceId").jsonPrimitive.content)
            }
        },
    )
}

internal class FlowFinalFailureScenario {
    private var results = emptyList<JsonObject>()
    fun bindings(): Map<String, () -> Unit> = mapOf(
        "Given all actions succeed and a requested final observation fails" to {},
        "When the caller receives the report" to {
            results = Platform.entries.map { platform ->
                FlowObservationFixture(platform).use { fixture ->
                    fixture.respond = { method -> MockResponse().setBody(when (method) {
                        "ui.getInteractiveElements" -> """
                            {"result":{"success":false,"count":0,"elements":[],"error":"inspection blocked"}}
                        """.trimIndent()
                        else -> """{"result":{"success":true}}"""
                    }) }
                    fixture.run("--step", "press_home", "--observe", "interactive-elements", "--observe", "hierarchy")
                }
            }
        },
        "Then the flow fails while the completed actions remain successful" to {
            results.forEach { result ->
                assertEquals("false", result.getValue("success").toString())
                assertEquals("1", result.getValue("exitCode").toString())
                assertEquals("null", result.getValue("failedStep").toString())
                assertEquals("succeeded", result.getValue("steps").jsonArray.single().jsonObject
                    .getValue("status").jsonPrimitive.content)
                assertEquals(listOf("failed", "skipped"), result.getValue("observations").jsonArray.map {
                    it.jsonObject.getValue("status").jsonPrimitive.content
                })
            }
        },
    )
}
