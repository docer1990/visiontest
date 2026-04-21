package com.example.visiontest.tools

import com.example.visiontest.android.AutomationClient
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.common.DeviceType
import com.example.visiontest.common.MobileDevice
import com.example.visiontest.discovery.ToolDiscovery
import kotlinx.coroutines.runBlocking
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
    fun `tapByCoordinates returns error when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val result = registrar.tapByCoordinates(100, 200)
        assertTrue(result.contains("Automation server is not running"))
    }

    @Test
    fun `getUiHierarchy returns error when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val result = registrar.getUiHierarchy()
        assertTrue(result.contains("Automation server is not running"))
    }

    @Test
    fun `pressBack returns error when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val result = registrar.pressBack()
        assertTrue(result.contains("Automation server is not running"))
    }

    @Test
    fun `pressHome returns error when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val result = registrar.pressHome()
        assertTrue(result.contains("Automation server is not running"))
    }

    @Test
    fun `inputText returns error when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val result = registrar.inputText("hello")
        assertTrue(result.contains("Automation server is not running"))
    }

    @Test
    fun `getDeviceInfo returns error when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val result = registrar.getDeviceInfo()
        assertTrue(result.contains("Automation server is not running"))
    }

    @Test
    fun `getInteractiveElements returns error when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val result = registrar.getInteractiveElements()
        assertTrue(result.contains("Automation server is not running"))
    }

    @Test
    fun `swipeByDirection returns error when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val result = registrar.swipeByDirection("up")
        assertTrue(result.contains("Automation server is not running"))
    }

    // --- findElement validation ---

    @Test
    fun `findElement requires at least one selector`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = registrar.findElement(null, null, null, null, null)
        assertTrue(result.contains("At least one selector required"))
    }

    @Test
    fun `findElement returns error when server not running`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        val result = registrar.findElement(text = "hello", textContains = null, resourceId = null, className = null, contentDescription = null)
        assertTrue(result.contains("Automation server is not running"))
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
}
