package com.example.visiontest.tools

import com.example.visiontest.CommandExecutionException
import com.example.visiontest.android.AutomationClient
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.common.DeviceType
import com.example.visiontest.common.MobileDevice
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [AndroidStopToolRegistrar]. The MockWebServer plays the automation
 * server's health endpoint; a recording [DeviceConfig] fake captures the
 * force-stop shell commands.
 */
class AndroidStopToolRegistrarTest {

    private lateinit var mockServer: MockWebServer

    /** DeviceConfig fake that records executeShell invocations (or throws on demand). */
    private class RecordingDeviceConfig : DeviceConfig {
        val shellCommands = mutableListOf<String>()
        var shellError: Exception? = null

        override suspend fun listDevices() = emptyList<MobileDevice>()
        override suspend fun getFirstAvailableDevice() = MobileDevice(
            id = "emulator-5554", name = "Pixel_6", type = DeviceType.ANDROID, state = "device"
        )
        override suspend fun listApps(deviceId: String?) = emptyList<String>()
        override suspend fun getAppInfo(packageName: String, deviceId: String?) = ""
        override suspend fun launchApp(packageName: String, activityName: String?, deviceId: String?) = false
        override suspend fun executeShell(command: String, deviceId: String?): String {
            shellError?.let { throw it }
            shellCommands.add(command)
            return ""
        }
    }

    private class RecordingAdbExecutor {
        val commands = mutableListOf<List<String>>()
        private val results = ArrayDeque<Result<String>>()

        fun enqueue(result: Result<String>) {
            results.addLast(result)
        }

        suspend fun execute(args: List<String>): String {
            commands += args
            return results.removeFirstOrNull()?.getOrThrow().orEmpty()
        }
    }

    @BeforeTest
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @AfterTest
    fun tearDown() {
        mockServer.shutdown()
    }

    private fun registrarWith(
        deviceConfig: DeviceConfig,
        executeAdb: suspend (List<String>) -> String = { "" },
    ): AndroidStopToolRegistrar {
        val client = AutomationClient(host = mockServer.hostName, port = mockServer.port)
        return AndroidStopToolRegistrar(deviceConfig, client, executeAdb)
    }

    @Test
    fun `stopAutomationServer force-stops packages and reports success`() = runBlocking {
        val recording = RecordingDeviceConfig()
        val adb = RecordingAdbExecutor()
        val registrar = registrarWith(recording, adb::execute)
        // wasRunning health check → running; shutdown verification → down
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        mockServer.enqueue(MockResponse().setResponseCode(500))

        val result = registrar.stopAutomationServer()

        assertTrue(result.contains("stopped successfully"))
        assertEquals(
            listOf(
                "am force-stop com.example.automationserver.test",
                "am force-stop com.example.automationserver",
            ),
            recording.shellCommands,
        )
        assertEquals(
            listOf(listOf("forward", "--remove", "tcp:9008")),
            adb.commands,
        )
    }

    @Test
    fun `stopAutomationServer is idempotent when server not running`() = runBlocking {
        val recording = RecordingDeviceConfig()
        val registrar = registrarWith(recording)
        mockServer.enqueue(MockResponse().setResponseCode(500))

        val result = registrar.stopAutomationServer()

        assertTrue(result.contains("was not running"))
        assertEquals(2, recording.shellCommands.size)
    }

    @Test
    fun `stopAutomationServer tolerates failed removal when forward is absent`() = runBlocking {
        val recording = RecordingDeviceConfig()
        val adb = RecordingAdbExecutor().apply {
            enqueue(Result.failure(CommandExecutionException("listener not found", 1)))
            enqueue(Result.success("emulator-5554 tcp:9010 tcp:9010\n"))
        }
        val registrar = registrarWith(recording, adb::execute)
        mockServer.enqueue(MockResponse().setResponseCode(500))

        val result = registrar.stopAutomationServer()

        assertTrue(result.contains("was not running"))
        assertEquals(
            listOf(
                listOf("forward", "--remove", "tcp:9008"),
                listOf("forward", "--list"),
            ),
            adb.commands,
        )
    }

    @Test
    fun `stopAutomationServer propagates removal failure when forward remains`() = runBlocking {
        val recording = RecordingDeviceConfig()
        val removalError = CommandExecutionException("remove failed", 1)
        val adb = RecordingAdbExecutor().apply {
            enqueue(Result.failure(removalError))
            enqueue(Result.success("emulator-5554 tcp:9008 tcp:9008\n"))
        }
        val registrar = registrarWith(recording, adb::execute)
        mockServer.enqueue(MockResponse().setResponseCode(500))

        val thrown = assertFailsWith<CommandExecutionException> { registrar.stopAutomationServer() }

        assertSame(removalError, thrown)
        assertEquals(
            listOf(
                listOf("forward", "--remove", "tcp:9008"),
                listOf("forward", "--list"),
            ),
            adb.commands,
        )
    }

    @Test
    fun `stopAutomationServer propagates removal failure when verification fails`() = runBlocking {
        val recording = RecordingDeviceConfig()
        val removalError = CommandExecutionException("remove failed", 1)
        val verificationError = CommandExecutionException("list failed", 1)
        val adb = RecordingAdbExecutor().apply {
            enqueue(Result.failure(removalError))
            enqueue(Result.failure(verificationError))
        }
        val registrar = registrarWith(recording, adb::execute)
        mockServer.enqueue(MockResponse().setResponseCode(500))

        val thrown = assertFailsWith<CommandExecutionException> { registrar.stopAutomationServer() }

        assertSame(removalError, thrown)
        assertEquals(listOf(verificationError), thrown.suppressed.toList())
        assertEquals(
            listOf(
                listOf("forward", "--remove", "tcp:9008"),
                listOf("forward", "--list"),
            ),
            adb.commands,
        )
    }

    @Test
    fun `stopAutomationServer propagates adb failures`() = runBlocking {
        val recording = RecordingDeviceConfig().apply {
            shellError = CommandExecutionException("adb: no devices/emulators found", 1)
        }
        val registrar = registrarWith(recording)
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        val ex = assertFailsWith<CommandExecutionException> { registrar.stopAutomationServer() }
        assertTrue(ex.message!!.contains("no devices"))
    }
}
