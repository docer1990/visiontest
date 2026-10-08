package com.example.visiontest.performance
import com.example.visiontest.tools.AndroidAutomationToolRegistrar
import com.example.visiontest.tools.IOSAutomationToolRegistrar
import com.example.visiontest.tools.AndroidWaitToolRegistrar
import com.example.visiontest.tools.IOSWaitToolRegistrar
import com.example.visiontest.tools.AndroidStopToolRegistrar
import com.example.visiontest.android.AutomationClient
import com.example.visiontest.android.AndroidElementSelectors
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.ios.IOSElementSelectors
import com.example.visiontest.discovery.ToolDiscovery
import org.slf4j.LoggerFactory
import kotlin.test.assertFailsWith
import com.example.visiontest.tools.ScreenshotSaver
import com.example.visiontest.tools.AndroidDeviceToolRegistrar
import com.example.visiontest.tools.IOSDeviceToolRegistrar
import com.example.visiontest.common.DeviceConfig
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import com.example.visiontest.ToolFactory
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class OperationTraceTest {

    @Test
    fun `invalid base64 returns failure and classifies decode span`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        val saver = ScreenshotSaver("Android", "android_screenshot", "APK", trace = trace)
        val target = File("unused.png")
        val result = trace.invocation("android_screenshot", "android") {
            saver.writeScreenshot(target, "!")
        }
        assertTrue(result.startsWith("Screenshot failed"))
        assertFalse(target.exists())
        val decode = events.single { it.stage == TraceStage.SCREENSHOT_DECODE }
        assertEquals(OperationOutcome.FAILURE, decode.operationOutcome)
        assertEquals(TraceOutcome.RETURNED, decode.outcome)
        assertFalse(events.any { it.stage == TraceStage.SCREENSHOT_WRITE })
    }

    @Test
    fun `device operations classify boolean results without changing returned text or calls`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        val device = mockk<DeviceConfig>()
        coEvery { device.launchApp(any(), any(), any()) } returns false
        val android = AndroidDeviceToolRegistrar(device, trace)
        val ios = IOSDeviceToolRegistrar(device, trace)
        val disabled = AndroidDeviceToolRegistrar(device)
        val expected = disabled.launchApp("secret.app")
        assertEquals(expected, trace.invocation("launch_app", "android") { android.launchApp("secret.app") })
        assertTrue(trace.invocation("ios_launch_app", "ios") { ios.launchApp("secret.app") }.contains("Failed"))
        val operations = events.filter { it.stage == TraceStage.OPERATION }
        assertEquals(2, operations.size)
        assertTrue(operations.all { it.operationOutcome == OperationOutcome.FAILURE })
        assertTrue(operations.all { it.outcome == TraceOutcome.RETURNED })
        assertTrue(events.none { it.toJson().toString().contains("secret.app") })
        coVerify(exactly = 3) { device.launchApp("secret.app", any(), any()) }
    }

    @Test
    fun `automation delegates waits and stop own one operation span`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        val device = mockk<DeviceConfig>()
        val androidClient = mockk<AutomationClient>()
        val iosClient = mockk<IOSAutomationClient>()
        val logger = LoggerFactory.getLogger(OperationTraceTest::class.java)
        val discovery = ToolDiscovery(logger)
        coEvery { androidClient.isServerRunning() } returns true
        coEvery { iosClient.isServerRunning() } returns true
        coEvery { androidClient.clearText() } returns "opaque secret result"
        coEvery { iosClient.dismissKeyboard(any()) } returns "opaque secret result"
        val android = AndroidAutomationToolRegistrar(device, androidClient, discovery, trace)
        val ios = IOSAutomationToolRegistrar(device, iosClient, discovery, logger, trace)
        val calls: List<suspend () -> Unit> = listOf(
            { assertEquals("opaque secret result", android.clearText()) },
            { assertEquals("opaque secret result", ios.dismissKeyboard()) },
            { assertFailsWith<IllegalArgumentException> {
                AndroidWaitToolRegistrar(androidClient, trace).waitForElement(AndroidElementSelectors(), 0)
            } },
            { assertFailsWith<IllegalArgumentException> {
                IOSWaitToolRegistrar(iosClient, trace).waitUntilGone(IOSElementSelectors(), 0)
            } },
            { assertTrue(ios.stopAutomationServer().contains("not running")) },
            {
                coEvery { androidClient.isServerRunning() } returns false
                coEvery { device.executeShell(any(), any()) } returns ""
                AndroidStopToolRegistrar(device, androidClient, trace, executeAdb = { "" }).stopAutomationServer()
            },
        )
        calls.forEach { call ->
            val before = events.size
            trace.invocation("other", null) { call() }
            assertEquals(1, events.drop(before).count { it.stage == TraceStage.OPERATION })
        }
        assertTrue(events.none { it.toJson().toString().contains("secret") })
        assertEquals(OperationOutcome.UNKNOWN, events.first { it.stage == TraceStage.OPERATION }.operationOutcome)
        coVerify(exactly = 1) { androidClient.clearText() }
        coVerify(exactly = 1) { iosClient.dismissKeyboard(null) }
    }

    @Test
    fun `screenshot stages preserve replacement bytes and classify handled IO failure`() = runTest {
        val directory = Files.createTempDirectory("trace-screenshot").toFile()
        try {
            val events = mutableListOf<TraceEvent>()
            val trace = TraceRecorder(emit = { events.add(it); Unit })
            val saver = ScreenshotSaver("iOS", "ios_screenshot", "bundle", trace)
            val target = File(directory, "private-image.png")
            target.writeText("old")
            val bytes = byteArrayOf(1, 2, 3)
            val base64 = Base64.getEncoder().encodeToString(bytes)
            val response = """{"result":{"success":true,"pngBase64":"$base64"}}"""
            trace.invocation("ios_screenshot", "ios") { saver.capture(target.path) { response } }
            assertTrue(bytes.contentEquals(target.readBytes()))
            assertEquals(1, events.count { it.stage == TraceStage.SCREENSHOT_PARSE })
            assertEquals(3L, events.single { it.stage == TraceStage.SCREENSHOT_DECODE }
                .metrics[TraceMetric.DECODED_BYTES])
            assertEquals(OperationOutcome.SUCCESS,
                events.single { it.stage == TraceStage.SCREENSHOT_WRITE }.operationOutcome)
            assertEquals(OperationOutcome.SUCCESS, events.single { it.stage == TraceStage.INVOCATION }.operationOutcome)
            events.clear()
            val badTarget = File(target, "child.png")
            val expected = ScreenshotSaver("iOS", "ios_screenshot", "bundle").capture(badTarget.path) { response }
            val actual = trace.invocation("ios_screenshot", "ios") { saver.capture(badTarget.path) { response } }
            assertEquals(expected, actual)
            assertEquals(OperationOutcome.FAILURE,
                events.single { it.stage == TraceStage.SCREENSHOT_WRITE }.operationOutcome)
            assertEquals(OperationOutcome.FAILURE, events.single { it.stage == TraceStage.INVOCATION }.operationOutcome)
            assertFalse(badTarget.exists())
            assertEquals(listOf(target.name), directory.listFiles()!!.map { it.name })
            assertTrue(events.none { it.toJson().toString().contains("private-image") })
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `screenshot envelope failure is returned and classified without decode`() = runTest {
        listOf("invalid-json", """{"result":{"success":false,"error":"private error"}}""",
            """{"error":{"code":-32601,"message":"private method"}}""").forEach { response ->
            val events = mutableListOf<TraceEvent>()
            val trace = TraceRecorder(emit = { events.add(it); Unit })
            val saver = ScreenshotSaver("Android", "android_screenshot", "APK", trace)
            val expected = ScreenshotSaver("Android", "android_screenshot", "APK").capture("unused.png") { response }
            val actual = trace.invocation("android_screenshot", "android") { saver.capture("unused.png") { response } }
            assertEquals(expected, actual)
            assertEquals(OperationOutcome.FAILURE,
                events.single { it.stage == TraceStage.SCREENSHOT_PARSE }.operationOutcome)
            assertTrue(events.none { it.stage == TraceStage.SCREENSHOT_DECODE })
            assertTrue(events.none { it.toJson().toString().contains("private") })
        }
    }

    @Test
    fun `factory MCP handler and direct CLI operation each emit one shared operation`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        val device = mockk<DeviceConfig>()
        coEvery { device.launchApp(any(), any(), any()) } returns true
        val logger = LoggerFactory.getLogger(OperationTraceTest::class.java)
        val server = mockk<Server>(relaxed = true)
        val factory = ToolFactory(device, device, logger, trace)
        factory.registerAllTools(server, trace)
        val slot = slot<suspend (CallToolRequest) -> CallToolResult>()
        verify {
            server.addTool(name = "launch_app_android", description = any(),
                inputSchema = any<Tool.Input>(), handler = capture(slot))
        }
        val mcp = slot.captured(CallToolRequest(name = "launch_app_android",
            arguments = JsonObject(mapOf("packageName" to JsonPrimitive("private.app")))))
        val cli = trace.invocation("launch_app", "android") {
            AndroidDeviceToolRegistrar(device, trace).launchApp("private.app")
        }
        assertEquals(cli, (mcp.content.single() as io.modelcontextprotocol.kotlin.sdk.TextContent).text)
        assertEquals(2, events.count { it.stage == TraceStage.INVOCATION })
        assertEquals(2, events.count { it.stage == TraceStage.OPERATION })
        val invocations = events.filter { it.stage == TraceStage.INVOCATION }
        invocations.forEach { invocation ->
            val operation = events.single {
                it.stage == TraceStage.OPERATION && it.invocationId == invocation.invocationId
            }
            assertEquals(invocation.spanId, operation.parentSpanId)
            assertEquals(OperationOutcome.SUCCESS, operation.operationOutcome)
        }
        coVerify(exactly = 2) { device.launchApp("private.app", any(), any()) }
    }

    @Test
    fun `status and selector validation classify authoritative operation branches`() = runTest {
        val device = mockk<DeviceConfig>()
        val androidClient = mockk<AutomationClient>()
        val iosClient = mockk<IOSAutomationClient>()
        val logger = LoggerFactory.getLogger(OperationTraceTest::class.java)
        val discovery = ToolDiscovery(logger)
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        val android = AndroidAutomationToolRegistrar(device, androidClient, discovery, trace)
        val ios = IOSAutomationToolRegistrar(device, iosClient, discovery, logger, trace)
        listOf(true, false).forEach { running ->
            coEvery { androidClient.isServerRunning() } returns running
            coEvery { iosClient.isServerRunning() } returns running
            trace.invocation("automation_server_status", "android") { android.automationServerStatus() }
            trace.invocation("ios_automation_server_status", "ios") { ios.automationServerStatus() }
            assertTrue(events.filter { it.stage == TraceStage.OPERATION }.takeLast(2).all {
                it.operationOutcome == if (running) OperationOutcome.SUCCESS else OperationOutcome.FAILURE
            })
        }
        coEvery { androidClient.isServerRunning() } returns true
        coEvery { iosClient.isServerRunning() } returns true
        trace.invocation("find_element", "android") { android.findElement(null, null, null, null, null) }
        trace.invocation("ios_find_element", "ios") { ios.findElement(null, null, null, null, null, null) }
        assertTrue(events.filter { it.stage == TraceStage.OPERATION }.takeLast(2).all {
            it.operationOutcome == OperationOutcome.FAILURE
        })
    }
    @Test
    fun `known missing server artifacts return classified failures`() = runTest {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        val device = mockk<DeviceConfig>()
        coEvery { device.getFirstAvailableDevice() } returns mockk()
        val androidClient = mockk<AutomationClient>()
        val iosClient = mockk<IOSAutomationClient>()
        coEvery { iosClient.isServerRunning() } returns false
        val logger = LoggerFactory.getLogger(OperationTraceTest::class.java)
        val discovery = mockk<ToolDiscovery>()
        every { discovery.findAutomationServerApk() } returns null
        every { discovery.findXctestrun() } returns null
        every { discovery.findXcodeProject() } returns null
        val android = AndroidAutomationToolRegistrar(device, androidClient, discovery, trace)
        val ios = IOSAutomationToolRegistrar(device, iosClient, discovery, logger, trace)
        assertTrue(trace.invocation("install_automation_server", "android") {
            android.installAutomationServer()
        }.contains("APK not found"))
        assertTrue(trace.invocation("ios_start_automation_server", "ios") {
            ios.startAutomationServer()
        }.contains("Neither pre-built"))
        assertTrue(events.filter { it.stage == TraceStage.OPERATION }.all {
            it.operationOutcome == OperationOutcome.FAILURE && it.outcome == TraceOutcome.RETURNED
        })
    }
}
