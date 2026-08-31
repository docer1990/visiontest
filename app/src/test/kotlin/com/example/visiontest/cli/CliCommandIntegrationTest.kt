package com.example.visiontest.cli

import com.example.visiontest.android.AutomationClient
import com.example.visiontest.cli.commands.*
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.common.DeviceType
import com.example.visiontest.common.MobileDevice
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.ios.IOSManager
import com.example.visiontest.android.AndroidElementSelectors
import com.example.visiontest.tools.AndroidAutomationToolRegistrar
import com.example.visiontest.tools.AndroidDeviceToolRegistrar
import com.example.visiontest.tools.AndroidStopToolRegistrar
import com.example.visiontest.tools.IOSAutomationToolRegistrar
import com.example.visiontest.tools.IOSDeviceToolRegistrar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.slf4j.LoggerFactory
import kotlin.test.*

/**
 * Integration-style tests: each test constructs a real CLI command with faked backends,
 * parses args, and verifies delegation produces expected output via [executeCliCommand].
 */
class CliCommandIntegrationTest {

    private lateinit var androidMock: MockWebServer
    private lateinit var iosMock: MockWebServer
    private lateinit var components: ComponentHolder
    private lateinit var androidStopRegistrar: AndroidStopToolRegistrar

    private val fakeDevice = MobileDevice(
        id = "emulator-5554", name = "Pixel_6", type = DeviceType.ANDROID, state = "device"
    )

    private val fakeDeviceConfig = object : DeviceConfig {
        override suspend fun listDevices() = listOf(fakeDevice)
        override suspend fun getFirstAvailableDevice() = fakeDevice
        override suspend fun listApps(deviceId: String?) = listOf("com.example.app")
        override suspend fun getAppInfo(packageName: String, deviceId: String?) = "version=1.0"
        override suspend fun launchApp(packageName: String, activityName: String?, deviceId: String?) = true
        override suspend fun executeShell(command: String, deviceId: String?) = ""
    }

    private val logger = LoggerFactory.getLogger(CliCommandIntegrationTest::class.java)

    @BeforeTest
    fun setUp() {
        androidMock = MockWebServer()
        androidMock.start()
        iosMock = MockWebServer()
        iosMock.start()

        val androidClient = AutomationClient(host = androidMock.hostName, port = androidMock.port)
        val iosClient = IOSAutomationClient(host = iosMock.hostName, port = iosMock.port)
        val discovery = ToolDiscovery(logger)

        // Use a real IOSManager but it won't be called for Android tests
        val iosManager = IOSManager(logger = logger)

        components = ComponentHolder(
            android = com.example.visiontest.android.Android(logger = logger),
            ios = iosManager,
            automationClient = androidClient,
            iosAutomationClient = iosClient,
            androidDeviceRegistrar = AndroidDeviceToolRegistrar(fakeDeviceConfig),
            androidAutomationRegistrar = AndroidAutomationToolRegistrar(fakeDeviceConfig, androidClient, discovery),
            iosDeviceRegistrar = IOSDeviceToolRegistrar(fakeDeviceConfig),
            iosAutomationRegistrar = IOSAutomationToolRegistrar(fakeDeviceConfig, iosClient, discovery, logger),
        )
        // Built against the fake device config so stop tests never touch real adb;
        // ComponentHolder's derived androidStopRegistrar would use the real Android above.
        androidStopRegistrar = AndroidStopToolRegistrar(fakeDeviceConfig, androidClient) { "" }
    }

    @AfterTest
    fun tearDown() {
        androidMock.shutdown()
        iosMock.shutdown()
    }

    // --- automation_server_status ---

    @Test
    fun `automation_server_status android when running`() {
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = executeCliCommand {
            components.androidAutomationRegistrar.automationServerStatus()
        }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout!!.contains("running"))
    }

    // --- tap_by_coordinates ---

    @Test
    fun `tap_by_coordinates parses and delegates`() {
        // Enqueue health check (for requireServerRunning) + health check (for requireServer) + tap response
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"jsonrpc":"2.0","id":1,"result":"Tapped at (100, 200)"}"""
        ))
        val result = executeCliCommand {
            requireServerRunning { components.isServerRunning(Platform.Android) }
            components.androidAutomationRegistrar.tapByCoordinates(100, 200)
        }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout!!.contains("100"))
        assertTrue(result.stdout!!.contains("200"))
    }

    // --- server not running → exit 3 ---

    @Test
    fun `command with server not running returns exit 3`() {
        // MockWebServer won't respond to health check → connection refused handled
        androidMock.shutdown() // force connection refused
        val result = executeCliCommand {
            requireServerRunning { components.isServerRunning(Platform.Android) }
            components.androidAutomationRegistrar.getUiHierarchy()
        }
        assertEquals(3, result.exitCode)
        assertTrue(result.stderr!!.contains("not running"))
    }

    // --- press_back rejects ios ---

    @Test
    fun `press_back rejects ios platform`() {
        val result = executeCliCommand {
            requireAndroid(Platform.Ios, "press_back")
            components.androidAutomationRegistrar.pressBack()
        }
        assertEquals(5, result.exitCode)
        assertTrue(result.stderr!!.contains("only supported on Android"))
    }

    // --- launch_app delegates to device registrar ---

    @Test
    fun `launch_app android delegates correctly`() {
        val result = executeCliCommand {
            components.androidDeviceRegistrar.launchApp("com.example.app")
        }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout!!.contains("com.example.app"))
    }

    // --- swipe_direction validates choices ---

    @Test
    fun `swipe_direction with valid args dispatches`() {
        // health check (requireServerRunning) + health check (requireServer) + swipe response
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"jsonrpc":"2.0","id":1,"result":"Swiped up"}"""
        ))
        val result = executeCliCommand {
            requireServerRunning { components.isServerRunning(Platform.Android) }
            components.androidAutomationRegistrar.swipeByDirection("up", "medium", "normal")
        }
        assertEquals(0, result.exitCode)
    }

    // --- stop_automation_server ---

    @Test
    fun `stop_automation_server android exits 0 when running`() {
        // wasRunning health check → running; shutdown verification → down
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        androidMock.enqueue(MockResponse().setResponseCode(500))
        val result = executeCliCommand {
            androidStopRegistrar.stopAutomationServer()
        }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout!!.contains("stopped successfully"))
    }

    @Test
    fun `stop_automation_server android exits 0 when not running`() {
        androidMock.enqueue(MockResponse().setResponseCode(500))
        val result = executeCliCommand {
            androidStopRegistrar.stopAutomationServer()
        }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout!!.contains("was not running"))
    }

    @Test
    fun `stop_automation_server ios exits 0 when not running`() {
        val result = executeCliCommand {
            components.iosAutomationRegistrar.stopAutomationServer()
        }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout!!.contains("not running"))
    }

    // --- wait_for_element ---

    @Test
    fun `wait_for_element exits 0 when element found`() {
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"jsonrpc":"2.0","id":1,"result":{"found":true,"text":"Login"}}"""
        ))
        val result = executeCliCommand {
            requireServerRunning { components.isServerRunning(Platform.Android) }
            components.androidWaitRegistrar.waitForElement(AndroidElementSelectors(text = "Login"), 5000)
        }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout!!.contains("found"))
    }

    @Test
    fun `wait_for_element exits 1 on timeout`() {
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"jsonrpc":"2.0","id":1,"result":{"found":false}}"""
        ))
        val result = executeCliCommand {
            requireServerRunning { components.isServerRunning(Platform.Android) }
            components.androidWaitRegistrar.waitForElement(AndroidElementSelectors(text = "Login"), 400)
        }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr!!.contains("text='Login'"))
    }

    @Test
    fun `wait_for_element exits 2 when no selector given`() {
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = executeCliCommand {
            requireServerRunning { components.isServerRunning(Platform.Android) }
            components.androidWaitRegistrar.waitForElement(AndroidElementSelectors())
        }
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr!!.contains("At least one selector required"))
    }

    @Test
    fun `wait_for_element exits 2 when timeout above cap`() {
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val result = executeCliCommand {
            requireServerRunning { components.isServerRunning(Platform.Android) }
            components.androidWaitRegistrar.waitForElement(AndroidElementSelectors(text = "Login"), 31_000)
        }
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr!!.contains("30000"))
    }

    @Test
    fun `wait_for_element exits 3 when server not running`() {
        androidMock.enqueue(MockResponse().setResponseCode(500))
        val result = executeCliCommand {
            requireServerRunning { components.isServerRunning(Platform.Android) }
            components.androidWaitRegistrar.waitForElement(AndroidElementSelectors(text = "Login"))
        }
        assertEquals(3, result.exitCode)
        assertTrue(result.stderr!!.contains("not running"))
    }

    // --- input_text ---

    @Test
    fun `input_text delegates with correct text`() {
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        androidMock.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"jsonrpc":"2.0","id":1,"result":"Text entered"}"""
        ))
        val result = executeCliCommand {
            requireServerRunning { components.isServerRunning(Platform.Android) }
            components.androidAutomationRegistrar.inputText("hello world")
        }
        assertEquals(0, result.exitCode)
    }
}
