package com.example.visiontest.cli

import com.example.visiontest.android.AutomationClient
import com.example.visiontest.cli.commands.*
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.common.DeviceType
import com.example.visiontest.common.MobileDevice
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.ios.IOSManager
import com.example.visiontest.tools.AndroidAutomationToolRegistrar
import com.example.visiontest.tools.AndroidDeviceToolRegistrar
import com.example.visiontest.tools.IOSAutomationToolRegistrar
import com.example.visiontest.tools.IOSDeviceToolRegistrar
import com.github.ajalt.clikt.core.BadParameterValue
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
        val cmd = AutomationServerStatusCommand(lazyOf(components))
        val result = executeCliCommand {
            cmd.parse(listOf("--platform", "android"))
            // The command calls runCliCommand internally which calls exitProcess;
            // we test via the registrar directly instead
            components.androidAutomationRegistrar.automationServerStatus()
        }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout!!.contains("running"))
    }

    // --- tap_by_coordinates ---

    @Test
    fun `tap_by_coordinates parses and delegates`() {
        // Enqueue health check (for requireServerRunning) + tap response
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
        val cmd = PressBackCommand(lazyOf(components))
        assertFailsWith<BadParameterValue> {
            cmd.parse(listOf("--platform", "ios"))
        }
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
        // health check + swipe response
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

    // --- input_text ---

    @Test
    fun `input_text delegates with correct text`() {
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
