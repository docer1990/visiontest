package com.example.visiontest.tools

import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.ios.IOSAutomationClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class IOSScreenshotToolTest {

    // Smallest valid PNG (1x1 transparent pixel) used as fixture.
    // Bytes: 89 50 4E 47 0D 0A 1A 0A ... (PNG magic header)
    private val fixturePngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    private val fixturePngBytes = Base64.getDecoder().decode(fixturePngBase64)

    private lateinit var mockServer: MockWebServer
    private lateinit var registrar: IOSAutomationToolRegistrar
    private lateinit var tempDir: File

    private val logger = LoggerFactory.getLogger(IOSScreenshotToolTest::class.java)
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
        val client = IOSAutomationClient(host = mockServer.hostName, port = mockServer.port)
        registrar = IOSAutomationToolRegistrar(fakeDeviceConfig, client, ToolDiscovery(logger), logger)
        tempDir = Files.createTempDirectory("ios-screenshot-test").toFile()
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
            resolved.name.matches(Regex("""ios_screenshot_\d{8}_\d{6}\.png""")),
            "Expected default filename to match ios_screenshot_<yyyyMMdd_HHmmss>.png, got ${resolved.name}"
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
        assertTrue(resolved.name.startsWith("ios_screenshot_"))
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

        assertTrue(message.contains("iOS automation server is not running"))
        assertFalse(target.exists(), "No file should be written when server is not running")
        assertEquals(1, mockServer.requestCount, "Only the health check should have been attempted")
    }

    @Test
    fun `success false in response surfaces as error and writes no file`() = runBlocking {
        enqueueHealthOk()
        enqueueScreenshotResult("""{"success":false,"error":"capture failed"}""")

        val target = File(tempDir, "out.png")
        val message = registrar.captureScreenshot(target.absolutePath)

        assertTrue(message.contains("Screenshot failed on the iOS automation server: capture failed"), "Got: $message")
        assertFalse(target.exists())
    }

    @Test
    fun `missing pngBase64 surfaces outdated-bundle hint and writes no file`() = runBlocking {
        enqueueHealthOk()
        enqueueScreenshotResult("""{"success":true}""")

        val target = File(tempDir, "out.png")
        val message = registrar.captureScreenshot(target.absolutePath)

        assertTrue(message.contains("response missing 'pngBase64'"), "Got: $message")
        assertTrue(message.contains("outdated iOS automation server bundle"), "Got: $message")
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
            message.contains("unable to parse response from iOS automation server"),
            "Got: $message"
        )
        assertFalse(target.exists())
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
