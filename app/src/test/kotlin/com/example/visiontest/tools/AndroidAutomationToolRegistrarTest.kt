package com.example.visiontest.tools

import com.example.visiontest.ServerNotRunningException
import com.example.visiontest.android.AndroidElementSelectors
import com.example.visiontest.android.AutomationClient
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.common.DeviceType
import com.example.visiontest.common.MobileDevice
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.utils.ErrorHandler
import com.google.gson.JsonParser
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.slf4j.LoggerFactory
import kotlin.test.*

/**
 * Tests for the extracted `internal suspend` functions on [AndroidAutomationToolRegistrar],
 * exercised directly without going through ToolScope/MCP.
 *
 * Screenshot-related methods are already thoroughly tested in [AndroidScreenshotToolTest];
 * this class covers the remaining extracted functions.
 */
class AndroidAutomationToolRegistrarTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var registrar: AndroidAutomationToolRegistrar

    private val logger = LoggerFactory.getLogger(AndroidAutomationToolRegistrarTest::class.java)
    private val fakeDeviceConfig = object : DeviceConfig {
        override suspend fun listDevices() = emptyList<MobileDevice>()
        override suspend fun getFirstAvailableDevice() = MobileDevice(
            id = "emulator-5554", name = "Pixel_6", type = DeviceType.ANDROID, state = "device"
        )
        override suspend fun listApps(deviceId: String?) = emptyList<String>()
        override suspend fun getAppInfo(packageName: String, deviceId: String?) = ""
        override suspend fun launchApp(packageName: String, activityName: String?, deviceId: String?) = false
        override suspend fun executeShell(command: String, deviceId: String?) = ""
    }

    @BeforeTest
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        val client = AutomationClient(host = mockServer.hostName, port = mockServer.port)
        registrar = AndroidAutomationToolRegistrar(fakeDeviceConfig, client, ToolDiscovery(logger))
    }

    @AfterTest
    fun tearDown() {
        mockServer.shutdown()
    }

    // --- automationServerStatus ---

    @Test
    fun `automationServerStatus when running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = registrar.automationServerStatus()
        assertTrue(result.contains("running and accessible"))
    }

    @Test
    fun `automationServerStatus when not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val result = registrar.automationServerStatus()
        assertTrue(result.contains("not running"))
    }

    // --- server-not-running guard on various functions ---

    @Test
    fun `tapByCoordinates throws when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val ex = assertFailsWith<ServerNotRunningException> { registrar.tapByCoordinates(100, 200) }
        assertTrue(ex.message!!.contains("not running"))
    }

    @Test
    fun `getUiHierarchy throws when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val ex = assertFailsWith<ServerNotRunningException> { registrar.getUiHierarchy() }
        assertTrue(ex.message!!.contains("not running"))
    }

    @Test
    fun `pressBack throws when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val ex = assertFailsWith<ServerNotRunningException> { registrar.pressBack() }
        assertTrue(ex.message!!.contains("not running"))
    }

    @Test
    fun `pressHome throws when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val ex = assertFailsWith<ServerNotRunningException> { registrar.pressHome() }
        assertTrue(ex.message!!.contains("not running"))
    }

    @Test
    fun `inputText throws when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val ex = assertFailsWith<ServerNotRunningException> { registrar.inputText("hello") }
        assertTrue(ex.message!!.contains("not running"))
    }

    @Test
    fun `getDeviceInfo throws when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val ex = assertFailsWith<ServerNotRunningException> { registrar.getDeviceInfo() }
        assertTrue(ex.message!!.contains("not running"))
    }

    @Test
    fun `getInteractiveElements throws when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val ex = assertFailsWith<ServerNotRunningException> { registrar.getInteractiveElements() }
        assertTrue(ex.message!!.contains("not running"))
    }

    @Test
    fun `swipeByDirection throws when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val ex = assertFailsWith<ServerNotRunningException> { registrar.swipeByDirection("up") }
        assertTrue(ex.message!!.contains("not running"))
    }

    // --- findElement validation ---

    @Test
    fun `findElement requires at least one selector`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = registrar.findElement(null, null, null, null, null)
        assertTrue(result.contains("At least one selector required"))
    }

    @Test
    fun `findElement throws when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val ex = assertFailsWith<ServerNotRunningException> {
            registrar.findElement(text = "hello", textContains = null, resourceId = null, className = null, contentDescription = null)
        }
        assertTrue(ex.message!!.contains("not running"))
    }

    // --- swipeOnElement validation ---

    @Test
    fun `swipeOnElement requires at least one selector`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = registrar.swipeOnElement("up", null, null, null, null, null)
        assertTrue(result.contains("At least one selector required"))
    }

    // --- tapByCoordinates delegates when server running ---

    @Test
    fun `tapByCoordinates delegates to automationClient`() = runBlocking {
        // health check
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        // tap response
        mockServer.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"Tapped at (100, 200)","id":1}"""))
        val result = registrar.tapByCoordinates(100, 200)
        // The raw JSON-RPC response is returned by AutomationClient
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `interaction validation happens before server access`() = runBlocking {
        assertFailsWith<IllegalArgumentException> { registrar.pressKey(null, null) }
        assertFailsWith<IllegalArgumentException> {
            registrar.longPress(10, 20, AndroidElementSelectors(text = "Menu"), 1_000)
        }
        assertFailsWith<IllegalArgumentException> {
            registrar.doubleTap(10, null, AndroidElementSelectors(), null)
        }
        assertFailsWith<IllegalArgumentException> {
            registrar.inputText("hello", AndroidElementSelectors(), 1_000)
        }
        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun `pressKey and selector longPress delegate normalized requests`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        mockServer.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
        registrar.pressKey(null, "ENTER")
        assertEquals("/health", mockServer.takeRequest().path)
        val keyRequest = JsonParser.parseString(mockServer.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.pressKey", keyRequest["method"].asString)
        assertEquals("enter", keyRequest["params"].asJsonObject["action"].asString)

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        mockServer.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
        registrar.longPress(null, null, AndroidElementSelectors(resourceId = "menu"), null)
        mockServer.takeRequest()
        val gestureRequest = JsonParser.parseString(mockServer.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("ui.longPress", gestureRequest["method"].asString)
        assertEquals(10_000, gestureRequest["params"].asJsonObject["timeoutMs"].asInt)
    }

    @Test
    fun `registered interaction tools expose bounded schemas`() {
        val server = mockk<Server>(relaxed = true)
        registrar.registerTools(ToolScope(server, logger))
        val pressKeySchema = slot<Tool.Input>()
        val longPressSchema = slot<Tool.Input>()
        val inputSchema = slot<Tool.Input>()
        verify { server.addTool("android_press_key", any(), capture(pressKeySchema), any()) }
        verify { server.addTool("android_clear_text", any(), any(), any()) }
        verify { server.addTool("android_long_press", any(), capture(longPressSchema), any()) }
        verify { server.addTool("android_double_tap", any(), any(), any()) }
        verify { server.addTool("android_input_text", any(), capture(inputSchema), any()) }

        assertTrue(longPressSchema.captured.required.orEmpty().isEmpty())
        assertEquals("integer", longPressSchema.captured.properties["x"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            Int.MAX_VALUE.toString(),
            pressKeySchema.captured.properties["keyCode"]!!.jsonObject["maximum"]!!.jsonPrimitive.content,
        )
        val timeout = longPressSchema.captured.properties["timeoutMs"]!!.jsonObject
        assertEquals("10000", timeout["default"]!!.jsonPrimitive.content)
        assertEquals("30000", timeout["maximum"]!!.jsonPrimitive.content)
        assertEquals(listOf("text"), inputSchema.captured.required)
        assertEquals(45_000L, INTERACTION_TOOL_TIMEOUT_MS)
    }

    @Test
    fun `interaction handlers reject unsupported arguments before backend access`() = runBlocking {
        val server = mockk<Server>(relaxed = true)
        registrar.registerTools(ToolScope(server, logger))
        val clearTextHandler = slot<suspend (CallToolRequest) -> CallToolResult>()
        val longPressHandler = slot<suspend (CallToolRequest) -> CallToolResult>()
        val doubleTapHandler = slot<suspend (CallToolRequest) -> CallToolResult>()
        val inputTextHandler = slot<suspend (CallToolRequest) -> CallToolResult>()
        verify { server.addTool("android_clear_text", any(), any(), capture(clearTextHandler)) }
        verify { server.addTool("android_long_press", any(), any(), capture(longPressHandler)) }
        verify { server.addTool("android_double_tap", any(), any(), capture(doubleTapHandler)) }
        verify { server.addTool("android_input_text", any(), any(), capture(inputTextHandler)) }

        assertInvalidArgument(clearTextHandler.captured, requestWith("unexpected", "value"))
        assertInvalidArgument(longPressHandler.captured, requestWith("bundleId", "app.id"))
        assertInvalidArgument(doubleTapHandler.captured, requestWith("bundleId", "app.id"))
        assertInvalidArgument(inputTextHandler.captured, requestWith("bundleId", "app.id"))
        assertEquals(0, mockServer.requestCount)
    }

    private suspend fun assertInvalidArgument(
        handler: suspend (CallToolRequest) -> CallToolResult,
        request: CallToolRequest,
    ) {
        val result = handler(request)
        assertTrue((result.content.single() as TextContent).text!!.contains(ErrorHandler.ERROR_INVALID_ARG))
    }

    private fun requestWith(key: String, value: String) = CallToolRequest(
        name = "test",
        arguments = buildJsonObject { put(key, value) },
    )
}
