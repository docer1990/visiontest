package com.example.visiontest.tools

import com.example.visiontest.CommandExecutionException
import com.example.visiontest.ServerNotRunningException
import com.example.visiontest.android.AndroidElementSelectors
import com.example.visiontest.android.AutomationClient
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.ios.IOSElementSelectors
import com.google.gson.JsonParser
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElementTapToolRegistrarTest {
    private lateinit var androidHttp: MockWebServer
    private lateinit var iosHttp: MockWebServer
    private lateinit var android: AndroidAutomationToolRegistrar
    private lateinit var ios: IOSAutomationToolRegistrar
    private val logger = LoggerFactory.getLogger(javaClass)

    @BeforeTest
    fun setUp() {
        androidHttp = MockWebServer().also { it.start() }
        iosHttp = MockWebServer().also { it.start() }
        val device = mockk<DeviceConfig>()
        android = AndroidAutomationToolRegistrar(device, AutomationClient(androidHttp.hostName, androidHttp.port), ToolDiscovery(logger))
        ios = IOSAutomationToolRegistrar(device, IOSAutomationClient(iosHttp.hostName, iosHttp.port), ToolDiscovery(logger), logger)
    }

    @AfterTest
    fun tearDown() {
        androidHttp.shutdown()
        iosHttp.shutdown()
    }

    @Test
    fun `element tap validates selectors scope and timeout before health checks`() = runBlocking {
        assertFailsWith<IllegalArgumentException> { android.tapOnElement(AndroidElementSelectors()) }
        assertFailsWith<IllegalArgumentException> { android.tapOnElement(AndroidElementSelectors(text = " ")) }
        assertFailsWith<IllegalArgumentException> { android.tapOnElement(AndroidElementSelectors(text = "a"), 30_001) }
        assertFailsWith<IllegalArgumentException> { ios.tapOnElement(IOSElementSelectors(bundleId = "app.id")) }
        assertFailsWith<IllegalArgumentException> { ios.tapOnElement(IOSElementSelectors(text = "a", bundleId = " ")) }
        assertEquals(0, androidHttp.requestCount)
        assertEquals(0, iosHttp.requestCount)
    }

    @Test
    fun `element tap reports unavailable server after validation`() = runBlocking {
        androidHttp.enqueue(MockResponse().setResponseCode(500))
        iosHttp.enqueue(MockResponse().setResponseCode(500))
        assertFailsWith<ServerNotRunningException> { android.tapOnElement(AndroidElementSelectors(text = "Login")) }
        assertFailsWith<ServerNotRunningException> { ios.tapOnElement(IOSElementSelectors(text = "Login")) }
    }

    @Test
    fun `element tap sends default timeout and returns original successful response`() = runBlocking {
        val raw = """{"jsonrpc":"2.0","id":1,"result":{"success":true}}"""
        androidHttp.enqueue(MockResponse().setBody("OK"))
        androidHttp.enqueue(MockResponse().setBody(raw))
        iosHttp.enqueue(MockResponse().setBody("OK"))
        iosHttp.enqueue(MockResponse().setBody(raw))

        assertEquals(raw, android.tapOnElement(AndroidElementSelectors(resourceId = "login")))
        assertEquals(raw, ios.tapOnElement(IOSElementSelectors(identifier = "login", bundleId = "app.id")))

        androidHttp.takeRequest()
        val androidRequest = JsonParser.parseString(androidHttp.takeRequest().body.readUtf8())
        assertEquals("ui.tapOnElement", androidRequest.asJsonObject["method"].asString)
        assertEquals(10_000, androidRequest.asJsonObject["params"].asJsonObject["timeoutMs"].asInt)
        iosHttp.takeRequest()
        val iosRequest = JsonParser.parseString(iosHttp.takeRequest().body.readUtf8())
        assertEquals("ui.tapOnElement", iosRequest.asJsonObject["method"].asString)
        assertEquals("app.id", iosRequest.asJsonObject["params"].asJsonObject["bundleId"].asString)
    }

    @Test
    fun `element tap maps operation failures to command errors on both platforms`() = runBlocking {
        val responses = listOf(
            """{"jsonrpc":"2.0","id":1,"result":{"success":false,"error":"not tappable"}}""",
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""",
            """{"jsonrpc":"2.0","id":1,"result":{}}""",
            "not JSON",
        )
        for (response in responses) {
            androidHttp.enqueue(MockResponse().setBody("OK"))
            androidHttp.enqueue(MockResponse().setBody(response))
            val error = assertFailsWith<CommandExecutionException> {
                android.tapOnElement(AndroidElementSelectors(text = "Login"))
            }
            assertTrue(error.message!!.isNotBlank())

            iosHttp.enqueue(MockResponse().setBody("OK"))
            iosHttp.enqueue(MockResponse().setBody(response))
            val iosError = assertFailsWith<CommandExecutionException> {
                ios.tapOnElement(IOSElementSelectors(text = "Login", bundleId = "app.id"))
            }
            assertTrue(iosError.message!!.isNotBlank())
        }
    }

    @Test
    fun `registered element tap tools expose schemas and handlers`() = runBlocking {
        val androidServer = mockk<Server>(relaxed = true)
        val iosServer = mockk<Server>(relaxed = true)
        android.registerTools(ToolScope(androidServer, logger))
        ios.registerTools(ToolScope(iosServer, logger))
        val androidInteractiveElementsDescription = slot<String>()
        val iosInteractiveElementsDescription = slot<String>()
        val androidSchema = slot<Tool.Input>()
        val androidHandler = slot<suspend (CallToolRequest) -> CallToolResult>()
        val iosSchema = slot<Tool.Input>()
        val iosHandler = slot<suspend (CallToolRequest) -> CallToolResult>()
        verify { androidServer.addTool("tap_on_element", any(), capture(androidSchema), capture(androidHandler)) }
        verify { iosServer.addTool("ios_tap_on_element", any(), capture(iosSchema), capture(iosHandler)) }
        verify {
            androidServer.addTool(
                "get_interactive_elements",
                capture(androidInteractiveElementsDescription),
                any(),
                any(),
            )
        }
        verify {
            iosServer.addTool(
                "ios_get_interactive_elements",
                capture(iosInteractiveElementsDescription),
                any(),
                any(),
            )
        }
        assertTrue(androidInteractiveElementsDescription.captured.contains("Prefer tap_on_element with a stable selector"))
        assertTrue(androidInteractiveElementsDescription.captured.contains("coordinates are the intended target"))
        assertTrue(iosInteractiveElementsDescription.captured.contains("Prefer ios_tap_on_element with a stable selector"))
        assertTrue(iosInteractiveElementsDescription.captured.contains("coordinates are the intended target"))
        assertTrue(androidSchema.captured.required.orEmpty().isEmpty())
        assertTrue(iosSchema.captured.required.orEmpty().isEmpty())
        assertEquals("integer", androidSchema.captured.properties["timeoutMs"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("10000", androidSchema.captured.properties["timeoutMs"]!!.jsonObject["default"]!!.jsonPrimitive.content)
        assertEquals("1", androidSchema.captured.properties["timeoutMs"]!!.jsonObject["minimum"]!!.jsonPrimitive.content)
        assertEquals("30000", androidSchema.captured.properties["timeoutMs"]!!.jsonObject["maximum"]!!.jsonPrimitive.content)
        assertTrue(iosSchema.captured.properties.containsKey("bundleId"))
        assertEquals("integer", iosSchema.captured.properties["timeoutMs"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("10000", iosSchema.captured.properties["timeoutMs"]!!.jsonObject["default"]!!.jsonPrimitive.content)
        assertEquals("1", iosSchema.captured.properties["timeoutMs"]!!.jsonObject["minimum"]!!.jsonPrimitive.content)
        assertEquals("30000", iosSchema.captured.properties["timeoutMs"]!!.jsonObject["maximum"]!!.jsonPrimitive.content)
        assertEquals(45_000L, ELEMENT_TAP_TOOL_TIMEOUT_MS)

        androidHttp.enqueue(MockResponse().setBody("OK"))
        androidHttp.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
        val result = androidHandler.captured(CallToolRequest("tap_on_element", JsonObject(mapOf("text" to JsonPrimitive("Login")))))
        assertFalse(result.isError == true)

        iosHttp.enqueue(MockResponse().setBody("OK"))
        iosHttp.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
        val iosResult = iosHandler.captured(
            CallToolRequest(
                "ios_tap_on_element",
                JsonObject(mapOf("resourceId" to JsonPrimitive("login"), "bundleId" to JsonPrimitive("app.id"))),
            )
        )
        assertFalse(iosResult.isError == true)
        assertEquals("/health", iosHttp.takeRequest().path)
        val iosRequest = JsonParser.parseString(iosHttp.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.tapOnElement", iosRequest["method"].asString)
        assertEquals("login", iosRequest["params"].asJsonObject["resourceId"].asString)
        assertEquals("app.id", iosRequest["params"].asJsonObject["bundleId"].asString)

        val failedResponses = listOf(
            """{"result":{"success":false,"error":"not tappable"}}""",
            """{"error":{"code":-32601,"message":"Method not found"}}""",
            """{"result":{}}""",
            "not JSON",
        )
        for (response in failedResponses) {
            iosHttp.enqueue(MockResponse().setBody("OK"))
            iosHttp.enqueue(MockResponse().setBody(response))
            val errorResult = iosHandler.captured(
                CallToolRequest("ios_tap_on_element", JsonObject(mapOf("text" to JsonPrimitive("Login")))),
            )
            assertTrue(errorResult.content.single().toString().contains("Command execution failed"))
        }
    }
}
