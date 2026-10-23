package dev.visiontest.cli

import dev.visiontest.android.AutomationClient
import dev.visiontest.cli.commands.GetInteractiveElementsCommand
import dev.visiontest.common.DeviceConfig
import dev.visiontest.discovery.ToolDiscovery
import dev.visiontest.ios.IOSAutomationClient
import dev.visiontest.tools.AndroidAutomationToolRegistrar
import dev.visiontest.tools.IOSAutomationToolRegistrar
import dev.visiontest.tools.ToolScope
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactInspectionAdaptersTest {
    companion object {
        fun scenarioBindings(): Map<String, () -> Map<String, () -> Unit>> = mapOf(
            "Request compact inspection through CLI and MCP" to {
                var fixtures = emptyList<Fixture>()
                mapOf(
                    "Given a running automation server with interactive elements" to {
                        fixtures = listOf(Fixture(false), Fixture(true))
                    },
                    "When the caller requests compact elements through CLI or MCP" to {
                        try { fixtures.forEach { it.assertParity() } }
                        finally { fixtures.forEach { it.close() } }
                    },
                    ("Then both interfaces return the same compact representation " +
                        "and reject invalid filters before device access") to {
                        for (ios in listOf(false, true)) Fixture(ios).use { it.assertInvalid() }
                    },
                )
            },
        )
    }

    @Test
    fun `compact failures preserve returned errors and reject malformed responses`() {
        for (ios in listOf(false, true)) Fixture(ios).use { fixture ->
            val failure = """{"success":false,"count":0,"elements":[],"error":"unavailable"}"""
            val rpc = """{"error":{"code":-32601,"message":"Unknown method"}}"""
            for ((raw, expected) in listOf("""{"result":$failure}""" to failure, rpc to rpc)) {
                fixture.respond(raw, cli = true)
                assertEquals(CliResult(0, expected, null), fixture.cli("--compact"))
                fixture.respond(raw)
                assertEquals(expected, fixture.mcp(buildJsonObject { put("compact", true) }))
            }
            for (raw in listOf("not json", """{"result":{"success":true}}""")) {
                fixture.respond(raw, cli = true)
                val result = fixture.cli("--compact")
                assertEquals(1, result.exitCode)
                assertNull(result.stdout)
                assertNotNull(result.stderr)
                fixture.respond(raw)
                assertTrue(fixture.mcp(buildJsonObject { put("compact", true) }).contains("Command execution failed"))
            }
        }
    }

    @Test
    fun `full mode remains unchanged and unavailable servers keep exit three`() {
        for (ios in listOf(false, true)) Fixture(ios).use { fixture ->
            fixture.respond(Fixture.raw, cli = true)
            assertEquals(Fixture.raw, fixture.cli().stdout)
            fixture.respond(Fixture.raw, cli = true)
            assertEquals(Fixture.payload, fixture.cli("--json").stdout)
            fixture.respond(Fixture.raw)
            assertEquals(Fixture.raw, fixture.mcp(buildJsonObject {}))
            fixture.http.enqueue(MockResponse().setResponseCode(500))
            assertEquals(3, fixture.cli("--compact").exitCode)
        }
    }
}

private class Fixture(private val ios: Boolean) : AutoCloseable {
    val http = MockWebServer().also { it.start() }
    private val logger = LoggerFactory.getLogger(javaClass)
    private val device = mockk<DeviceConfig>()
    private val androidClient = AutomationClient(http.hostName, http.port)
    private val iosClient = IOSAutomationClient(http.hostName, http.port)
    private val android = AndroidAutomationToolRegistrar(device, androidClient, ToolDiscovery(logger))
    private val apple = IOSAutomationToolRegistrar(device, iosClient, ToolDiscovery(logger), logger)
    private val components = lazy {
        ComponentHolder(mockk(), mockk(), androidClient, iosClient, mockk(), android, mockk(), apple)
    }
    private val name = if (ios) "ios_get_interactive_elements" else "get_interactive_elements"
    private val handler = slot<suspend (CallToolRequest) -> CallToolResult>()
    private val schema = slot<Tool.Input>()

    init {
        val server = mockk<Server>(relaxed = true)
        if (ios) apple.registerTools(ToolScope(server, logger)) else android.registerTools(ToolScope(server, logger))
        verify { server.addTool(name, any(), capture(schema), capture(handler)) }
    }

    fun cli(vararg args: String): CliResult {
        var result: CliResult? = null
        val parsed = executeCliCommand {
            GetInteractiveElementsCommand(components) { result = executeCliCommand(it) }
                .parse(listOf("-p", if (ios) "ios" else "android") + args)
            ""
        }
        return result ?: parsed
    }

    fun mcp(arguments: JsonObject): String = runBlocking {
        (handler.captured(CallToolRequest(name, arguments)).content.single() as TextContent).text!!
    }

    fun respond(raw: String, cli: Boolean = false) {
        if (cli) http.enqueue(MockResponse().setBody("OK"))
        http.enqueue(MockResponse().setBody("OK"))
        http.enqueue(MockResponse().setBody(raw))
    }

    fun assertParity() {
        val properties = schema.captured.properties.keys
        assertTrue(properties.containsAll(listOf("compact", "type", "region", "includeBounds", "includeDisabled")))
        assertEquals(ios, "bundleId" in properties)
        for (key in properties) {
            val expectedType = if (key in setOf("compact", "includeBounds", "includeDisabled")) "boolean" else "string"
            val property = schema.captured.properties.getValue(key) as JsonObject
            assertEquals(Json.parseToJsonElement("\"$expectedType\""), property["type"])
        }
        val args = listOf(
            "--compact", "--type", "Button", "--region", "0,0,20,20", "--include-bounds", "--include-disabled"
        ) +
            if (ios) listOf("--bundle-id", "app.id") else emptyList()
        val input = buildJsonObject {
            put("compact", true); put("type", "Button"); put("region", "0,0,20,20")
            put("includeBounds", true); put("includeDisabled", true)
            if (ios) put("bundleId", "app.id")
        }
        respond(raw, cli = true)
        val result = cli(*args.toTypedArray())
        assertEquals(0, result.exitCode, result.stderr)
        assertEquals(expected, result.stdout)
        assertWire(cli = true)
        respond(raw)
        assertEquals(expected, mcp(input))
        assertWire(cli = false)
        respond(raw, cli = true)
        assertEquals(expected, cli(*(args + "--json").toTypedArray()).stdout)
    }

    private fun assertWire(cli: Boolean) {
        if (cli) assertEquals("/health", http.takeRequest().path)
        assertEquals("/health", http.takeRequest().path)
        val wire = Json.parseToJsonElement(http.takeRequest().body.readUtf8()) as JsonObject
        assertEquals(buildJsonObject {
            put("includeDisabled", true)
            if (ios) put("bundleId", "app.id")
        }, wire["params"])
    }

    fun assertInvalid() {
        for (args in listOf(listOf("--type", "Button"), listOf("--region", "0,0,1,1"),
            listOf("--include-bounds"), listOf("--compact", "--type", " "),
            listOf("--compact", "--region", "0,0,0,1"))) {
            assertEquals(2, cli(*args.toTypedArray()).exitCode)
        }
        for (input in listOf(buildJsonObject { put("type", "Button") },
            buildJsonObject { put("region", "0,0,1,1") }, buildJsonObject { put("includeBounds", true) },
            buildJsonObject { put("compact", true); put("type", " ") },
            buildJsonObject { put("compact", true); put("region", "0,0,0,1") },
            buildJsonObject { put("compact", "true") }, buildJsonObject { put("includeBounds", "true") },
            buildJsonObject { put("type", 1) }, buildJsonObject { put("region", 1) })) {
            assertTrue(mcp(input).contains("Invalid argument"))
        }
        assertFalse(components.isInitialized())
        assertEquals(0, http.requestCount)
    }

    override fun close() = http.shutdown()

    companion object {
        const val payload = """{"success":true,"count":2,"elements":[""" +
            """{"text":"Login","className":"Button","centerX":10,"centerY":10,"isEnabled":false},""" +
            """{"text":"Other","className":"Text","centerX":30,"centerY":30}]}"""
        const val raw = """{"jsonrpc":"2.0","id":17,"result":$payload}"""
        const val expected = """{"success":true,"format":"compact-v1","count":1,"observedCount":2,"elements":[""" +
            """{"selector":{"text":"Login"},"label":"Login","type":"Button","enabled":false,""" +
            """"centerX":10,"centerY":10}]}"""
    }
}
