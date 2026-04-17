package com.example.visiontest.tools

import com.example.visiontest.android.AutomationClient
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.discovery.ToolDiscovery
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class AndroidScreenshotToolTest {

    // Smallest valid PNG (1x1 transparent pixel) used as fixture.
    // Bytes: 89 50 4E 47 0D 0A 1A 0A ... (PNG magic header)
    private val fixturePngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    private val fixturePngBytes = Base64.getDecoder().decode(fixturePngBase64)

    private lateinit var mockServer: MockWebServer
    private lateinit var registrar: AndroidAutomationToolRegistrar
    private lateinit var tempDir: File

    private val logger = LoggerFactory.getLogger(AndroidScreenshotToolTest::class.java)
    private val fakeDeviceConfig = object : DeviceConfig {
        override suspend fun listDevices() = emptyList<com.example.visiontest.common.MobileDevice>()
        override suspend fun getFirstAvailableDevice(): com.example.visiontest.common.MobileDevice =
            throw UnsupportedOperationException()
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
        tempDir = Files.createTempDirectory("android-screenshot-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        mockServer.shutdown()
        tempDir.deleteRecursively()
    }

    // --- resolveScreenshotPath ---

    @Test
    fun `default path places timestamped filename under screenshots in CWD`() {
        val resolved = registrar.resolveScreenshotPath(null)
        assertEquals("screenshots", resolved.parentFile.name)
        assertTrue(
            resolved.name.matches(Regex("""android_screenshot_\d{8}_\d{6}\.png""")),
            "Expected default filename to match android_screenshot_<yyyyMMdd_HHmmss>.png, got ${resolved.name}"
        )
        assertTrue(resolved.isAbsolute, "Default path should be absolute")
        // Default must be relative to the JVM's working directory (the user's project when
        // launched by a coding agent), NOT the visiontest install dir.
        val expectedRoot = File(System.getProperty("user.dir")).absoluteFile
        assertEquals(expectedRoot, resolved.parentFile.parentFile)
    }

    @Test
    fun `explicit outputPath is used verbatim`() {
        val explicit = File(tempDir, "custom/shot.png")
        val resolved = registrar.resolveScreenshotPath(explicit.absolutePath)
        assertEquals(explicit.absolutePath, resolved.absolutePath)
    }

    @Test
    fun `blank outputPath falls back to default`() {
        val resolved = registrar.resolveScreenshotPath("   ")
        assertTrue(resolved.name.startsWith("android_screenshot_"))
    }

    // --- captureScreenshot end-to-end via MockWebServer ---

    @Test
    fun `successful capture writes decoded PNG bytes to target path`() = runBlocking {
        enqueueHealthOk()
        enqueueScreenshotResult(successBody(fixturePngBase64))

        val target = File(tempDir, "out.png")
        val message = registrar.captureScreenshot(target.absolutePath)

        assertTrue(message.contains("Screenshot saved to ${target.absolutePath}"), "Got: $message")
        assertTrue(target.exists(), "PNG file should be written")
        assertContentEquals(fixturePngBytes, target.readBytes())
    }

    @Test
    fun `missing parent directories are created automatically`() = runBlocking {
        enqueueHealthOk()
        enqueueScreenshotResult(successBody(fixturePngBase64))

        val nested = File(tempDir, "a/b/c/out.png")
        assertFalse(nested.parentFile.exists(), "precondition: parent should not exist")

        registrar.captureScreenshot(nested.absolutePath)

        assertTrue(nested.exists())
        assertContentEquals(fixturePngBytes, nested.readBytes())
    }

    @Test
    fun `server not running short-circuits with no file write`() = runBlocking {
        // Health check fails with 500; no second request should be made.
        mockServer.enqueue(MockResponse().setResponseCode(500))

        val target = File(tempDir, "out.png")
        val message = registrar.captureScreenshot(target.absolutePath)

        assertTrue(message.contains("Automation server is not running"))
        assertFalse(target.exists(), "No file should be written when server is not running")
        assertEquals(1, mockServer.requestCount, "Only the health check should have been attempted")
    }

    @Test
    fun `success false in response surfaces as error and writes no file`() = runBlocking {
        enqueueHealthOk()
        enqueueScreenshotResult("""{"success":false,"error":"capture failed"}""")

        val target = File(tempDir, "out.png")
        val message = registrar.captureScreenshot(target.absolutePath)

        assertTrue(message.contains("Screenshot failed on the Android automation server: capture failed"), "Got: $message")
        assertFalse(target.exists())
    }

    @Test
    fun `missing pngBase64 surfaces outdated-APK hint and writes no file`() = runBlocking {
        enqueueHealthOk()
        enqueueScreenshotResult("""{"success":true}""")

        val target = File(tempDir, "out.png")
        val message = registrar.captureScreenshot(target.absolutePath)

        assertTrue(message.contains("response missing 'pngBase64'"), "Got: $message")
        assertTrue(message.contains("outdated Android automation server APK"), "Got: $message")
        assertFalse(target.exists())
    }

    @Test
    fun `missing result object surfaces error and writes no file`() = runBlocking {
        enqueueHealthOk()
        // Response with no "result" wrapper at all.
        mockServer.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1}"""))

        val target = File(tempDir, "out.png")
        val message = registrar.captureScreenshot(target.absolutePath)

        assertTrue(message.contains("response missing 'result' object"), "Got: $message")
        assertFalse(target.exists())
    }

    @Test
    fun `malformed JSON response surfaces parse error and writes no file`() = runBlocking {
        enqueueHealthOk()
        mockServer.enqueue(MockResponse().setBody("not json"))

        val target = File(tempDir, "out.png")
        val message = registrar.captureScreenshot(target.absolutePath)

        assertTrue(
            message.contains("unable to parse response from Android automation server"),
            "Got: $message"
        )
        assertFalse(target.exists())
    }

    @Test
    fun `JSON-RPC methodNotFound maps to outdated-APK guidance`() = runBlocking {
        enqueueHealthOk()
        // Standard JSON-RPC 2.0 error envelope for methodNotFound (code -32601).
        // This is what an older pre-built APK would return for ui.screenshot.
        mockServer.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","error":{"code":-32601,"message":"Method not found: ui.screenshot"},"id":1}"""
            )
        )

        val target = File(tempDir, "out.png")
        val message = registrar.captureScreenshot(target.absolutePath)

        assertTrue(message.contains("methodNotFound"), "Got: $message")
        assertTrue(message.contains("outdated Android automation server APK"), "Got: $message")
        assertFalse(target.exists())
    }

    @Test
    fun `other JSON-RPC errors surface code and message`() = runBlocking {
        enqueueHealthOk()
        mockServer.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","error":{"code":-32000,"message":"UIAutomator crashed"},"id":1}"""
            )
        )

        val target = File(tempDir, "out.png")
        val message = registrar.captureScreenshot(target.absolutePath)

        assertTrue(message.contains("-32000"), "Got: $message")
        assertTrue(message.contains("UIAutomator crashed"), "Got: $message")
        assertFalse(target.exists())
    }

    @Test
    fun `result present but not a JSON object is rejected gracefully`() = runBlocking {
        enqueueHealthOk()
        mockServer.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"oops","id":1}"""))

        val target = File(tempDir, "out.png")
        val message = registrar.captureScreenshot(target.absolutePath)

        assertTrue(message.contains("'result' is not a JSON object"), "Got: $message")
        assertFalse(target.exists())
    }

    @Test
    fun `invalid base64 in pngBase64 surfaces a decode error and writes no file`() = runBlocking {
        enqueueHealthOk()
        enqueueScreenshotResult(successBody("!!!not-base64!!!"))

        val target = File(tempDir, "out.png")
        val message = registrar.captureScreenshot(target.absolutePath)

        assertTrue(message.contains("invalid base64 PNG data"), "Got: $message")
        assertFalse(target.exists())
    }

    @Test
    fun `atomic write leaves no temp file on success`() = runBlocking {
        enqueueHealthOk()
        enqueueScreenshotResult(successBody(fixturePngBase64))

        val target = File(tempDir, "out.png")
        registrar.captureScreenshot(target.absolutePath)

        // Confirm the final file exists and no .png.tmp sidecar was left behind
        assertTrue(target.exists())
        val leftovers = tempDir.listFiles { f -> f.name.endsWith(".png.tmp") }.orEmpty()
        assertEquals(0, leftovers.size, "Temp files left behind: ${leftovers.map { it.name }}")
    }

    // --- helpers ---

    private fun enqueueHealthOk() {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
    }

    private fun enqueueScreenshotResult(innerResultJson: String) {
        val body = """{"jsonrpc":"2.0","result":$innerResultJson,"id":1}"""
        mockServer.enqueue(MockResponse().setBody(body))
    }

    private fun successBody(pngBase64: String): String =
        """{"success":true,"pngBase64":"$pngBase64"}"""
}
