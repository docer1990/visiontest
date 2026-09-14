package com.example.visiontest.tools

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
import kotlinx.serialization.json.jsonArray
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

class IOSInteractionToolRegistrarTest {
    private lateinit var http: MockWebServer
    private lateinit var registrar: IOSAutomationToolRegistrar
    private val logger = LoggerFactory.getLogger(javaClass)

    @BeforeTest
    fun setUp() {
        http = MockWebServer().also { it.start() }
        registrar = IOSAutomationToolRegistrar(
            mockk<DeviceConfig>(), IOSAutomationClient(http.hostName, http.port), ToolDiscovery(logger), logger
        )
    }

    @AfterTest
    fun tearDown() = http.shutdown()

    @Test
    fun `invalid interaction requests fail before server access`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            registrar.longPress(1, 2, IOSElementSelectors(text = "Menu"), null)
        }
        assertFailsWith<IllegalArgumentException> {
            registrar.doubleTap(null, null, IOSElementSelectors(bundleId = "app.id"), null)
        }
        assertFailsWith<IllegalArgumentException> { registrar.handleAlert("later", null, null) }
        assertFailsWith<IllegalArgumentException> { registrar.dismissKeyboard(" ") }
        assertFailsWith<IllegalArgumentException> {
            registrar.inputText("Ada", null, null, 500)
        }
        assertEquals(0, http.requestCount)
    }

    @Test
    fun `selector timeout boundaries are accepted and rejected before server access`() = runBlocking {
        val selector = IOSElementSelectors(identifier = "menu")
        assertFailsWith<IllegalArgumentException> {
            registrar.longPress(null, null, selector, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            registrar.doubleTap(null, null, selector, 30_001)
        }
        assertEquals(0, http.requestCount)

        enqueueSuccessfulCall()
        registrar.longPress(null, null, selector, 1)
        val minimum = takeRpcRequest()
        assertEquals("ui.longPress", minimum["method"].asString)
        assertEquals("1", minimum.stringParams()["timeoutMs"])

        enqueueSuccessfulCall()
        registrar.doubleTap(null, null, selector, 30_000)
        val maximum = takeRpcRequest()
        assertEquals("ui.doubleTap", maximum["method"].asString)
        assertEquals("30000", maximum.stringParams()["timeoutMs"])
    }

    @Test
    fun `alert propagates normalized action exact label and app scope`() = runBlocking {
        enqueueSuccessfulCall()
        registrar.handleAlert("ACCEPT", "Allow", "app.id")
        val alert = takeRpcRequest()

        assertEquals("ui.handleAlert", alert["method"].asString)
        assertEquals(
            mapOf("action" to "accept", "buttonLabel" to "Allow", "bundleId" to "app.id"),
            alert.stringParams(),
        )
    }

    @Test
    fun `targeted input maps app scope all selectors and timeout`() = runBlocking {
        enqueueSuccessfulCall()
        registrar.inputText(
            text = "Ada",
            bundleId = "app.id",
            selectors = IOSElementSelectors("Exact", "Partial", "name", "TextField", "Name"),
            timeoutMs = 2_000,
        )
        val input = takeRpcRequest()

        assertEquals("ui.inputText", input["method"].asString)
        assertEquals(
            mapOf(
                "text" to "Ada",
                "bundleId" to "app.id",
                "targetText" to "Exact",
                "targetTextContains" to "Partial",
                "targetResourceId" to "name",
                "targetClassName" to "TextField",
                "targetContentDescription" to "Name",
                "timeoutMs" to "2000",
            ),
            input.stringParams(),
        )
    }

    @Test
    fun `coordinate and selector gestures route to matching client overloads`() = runBlocking {
        enqueueSuccessfulCall()
        registrar.longPress(10, 20, IOSElementSelectors(), null)
        val coordinate = takeRpcRequest()
        assertEquals("ui.longPress", coordinate["method"].asString)
        assertEquals(mapOf("x" to "10", "y" to "20"), coordinate.stringParams())

        enqueueSuccessfulCall()
        registrar.longPress(null, null, IOSElementSelectors(identifier = "menu", bundleId = "app.id"), null)
        val gesture = takeRpcRequest()
        assertEquals("ui.longPress", gesture["method"].asString)
        assertEquals(
            mapOf("timeoutMs" to "10000", "resourceId" to "menu", "bundleId" to "app.id"),
            gesture.stringParams(),
        )
    }

    @Test
    fun `registered iOS interaction tools expose schemas and adapt MCP requests`() = runBlocking {
        val server = mockk<Server>(relaxed = true)
        registrar.registerTools(ToolScope(server, logger))
        val gestureSchema = slot<Tool.Input>()
        val alertSchema = slot<Tool.Input>()
        val inputSchema = slot<Tool.Input>()
        val dismissKeyboardHandler = slot<suspend (CallToolRequest) -> CallToolResult>()
        val alertHandler = slot<suspend (CallToolRequest) -> CallToolResult>()
        val longPressHandler = slot<suspend (CallToolRequest) -> CallToolResult>()
        val doubleTapHandler = slot<suspend (CallToolRequest) -> CallToolResult>()
        val inputHandler = slot<suspend (CallToolRequest) -> CallToolResult>()
        verify { server.addTool("ios_dismiss_keyboard", any(), any(), capture(dismissKeyboardHandler)) }
        verify { server.addTool("ios_handle_alert", any(), capture(alertSchema), capture(alertHandler)) }
        verify { server.addTool("ios_long_press", any(), capture(gestureSchema), capture(longPressHandler)) }
        verify { server.addTool("ios_double_tap", any(), any(), capture(doubleTapHandler)) }
        verify { server.addTool("ios_input_text", any(), capture(inputSchema), capture(inputHandler)) }
        assertEquals(listOf("action"), alertSchema.captured.required)
        assertEquals(
            listOf("accept", "dismiss"),
            alertSchema.captured.properties["action"]!!.jsonObject["enum"]!!.jsonArray
                .map { it.jsonPrimitive.content },
        )
        assertTimeoutSchema(gestureSchema.captured)
        assertEquals(true, gestureSchema.captured.properties.containsKey("bundleId"))
        assertEquals(listOf("text"), inputSchema.captured.required)
        assertEquals(true, inputSchema.captured.properties.containsKey("targetResourceId"))
        assertTimeoutSchema(inputSchema.captured)

        enqueueSuccessfulCall()
        val dismissResult = dismissKeyboardHandler.captured(
            request("ios_dismiss_keyboard", "bundleId" to JsonPrimitive("app.id")),
        )
        assertFalse(dismissResult.isError == true)
        val dismiss = takeRpcRequest()
        assertEquals("ui.dismissKeyboard", dismiss["method"].asString)
        assertEquals(mapOf("bundleId" to "app.id"), dismiss.stringParams())

        enqueueSuccessfulCall()
        val alertResult = alertHandler.captured(
            request(
                "ios_handle_alert",
                "action" to JsonPrimitive("dismiss"),
                "buttonLabel" to JsonPrimitive("Not Now"),
                "bundleId" to JsonPrimitive("app.id"),
            ),
        )
        assertFalse(alertResult.isError == true)
        assertEquals(
            mapOf("action" to "dismiss", "buttonLabel" to "Not Now", "bundleId" to "app.id"),
            takeRpcRequest().stringParams(),
        )

        enqueueSuccessfulCall()
        val gestureResult = longPressHandler.captured(
            request(
                "ios_long_press",
                "x" to JsonPrimitive(12),
                "y" to JsonPrimitive(34),
            ),
        )
        assertFalse(gestureResult.isError == true)
        val gesture = takeRpcRequest()
        assertEquals("ui.longPress", gesture["method"].asString)
        assertEquals(mapOf("x" to "12", "y" to "34"), gesture.stringParams())

        enqueueSuccessfulCall()
        val selectorGestureResult = doubleTapHandler.captured(
            request(
                "ios_double_tap",
                "resourceId" to JsonPrimitive("menu"),
                "bundleId" to JsonPrimitive("app.id"),
                "timeoutMs" to JsonPrimitive(2_500),
            ),
        )
        assertFalse(selectorGestureResult.isError == true)
        val selectorGesture = takeRpcRequest()
        assertEquals("ui.doubleTap", selectorGesture["method"].asString)
        assertEquals(
            mapOf("resourceId" to "menu", "bundleId" to "app.id", "timeoutMs" to "2500"),
            selectorGesture.stringParams(),
        )

        enqueueSuccessfulCall()
        val inputResult = inputHandler.captured(
            request(
                "ios_input_text",
                "text" to JsonPrimitive("Ada"),
                "targetText" to JsonPrimitive("Exact"),
                "targetTextContains" to JsonPrimitive("Partial"),
                "targetResourceId" to JsonPrimitive("name"),
                "targetClassName" to JsonPrimitive("TextField"),
                "targetContentDescription" to JsonPrimitive("Name"),
                "bundleId" to JsonPrimitive("app.id"),
                "timeoutMs" to JsonPrimitive(1_250),
            ),
        )
        assertFalse(inputResult.isError == true)
        val input = takeRpcRequest()
        assertEquals("ui.inputText", input["method"].asString)
        assertEquals(
            mapOf(
                "text" to "Ada",
                "bundleId" to "app.id",
                "targetText" to "Exact",
                "targetTextContains" to "Partial",
                "targetResourceId" to "name",
                "targetClassName" to "TextField",
                "targetContentDescription" to "Name",
                "timeoutMs" to "1250",
            ),
            input.stringParams(),
        )
    }

    private fun enqueueSuccessfulCall() {
        http.enqueue(MockResponse().setBody("OK"))
        http.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
    }

    private fun takeRpcRequest(): com.google.gson.JsonObject {
        assertEquals("/health", http.takeRequest().path)
        return JsonParser.parseString(http.takeRequest().body.readUtf8()).asJsonObject
    }

    private fun com.google.gson.JsonObject.stringParams(): Map<String, String> =
        getAsJsonObject("params").entrySet().associate { it.key to it.value.asString }

    private fun request(name: String, vararg arguments: Pair<String, JsonPrimitive>) =
        CallToolRequest(name, JsonObject(mapOf(*arguments)))

    private fun assertTimeoutSchema(schema: Tool.Input) {
        val timeout = schema.properties["timeoutMs"]!!.jsonObject
        assertEquals("integer", timeout["type"]!!.jsonPrimitive.content)
        assertEquals("1", timeout["minimum"]!!.jsonPrimitive.content)
        assertEquals("30000", timeout["maximum"]!!.jsonPrimitive.content)
        assertEquals("10000", timeout["default"]!!.jsonPrimitive.content)
    }
}
