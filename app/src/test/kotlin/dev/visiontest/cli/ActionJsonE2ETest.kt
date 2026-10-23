package dev.visiontest.cli

import dev.visiontest.config.AutomationConfig
import dev.visiontest.config.IOSAutomationConfig
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.net.BindException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("e2e")
class ActionJsonE2ETest {
    @TempDir lateinit var directory: Path

    @Test
    fun `Android gateway emits one object and newline or empty stdout on invalid result`() =
        assertGateway(Platform.Android, AutomationConfig.DEFAULT_PORT)

    @Test
    fun `iOS gateway emits one object and newline or empty stdout on invalid result`() =
        assertGateway(Platform.Ios, IOSAutomationConfig.DEFAULT_PORT)

    private fun assertGateway(platform: Platform, port: Int) {
        MockWebServer().use { server ->
            var response = """{"jsonrpc":"2.0","id":17,"result":{"success":true,"extra":"kept"}}"""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setBody(if (request.method == "GET") "OK" else response)
            }
            // Bind before launching the CLI so an occupied port cannot target a real device server.
            try {
                server.start(port)
            } catch (_: BindException) {
                assumeTrue(false, "$platform automation port is occupied; no live server was contacted")
            }
            val success = runAction(platform)
            assertEquals(0, success.first)
            assertEquals("{\"success\":true,\"extra\":\"kept\"}\n", success.second)
            response = """{"result":{"success":"true"}}"""
            val failure = runAction(platform)
            assertEquals(1, failure.first)
            assertEquals("", failure.second)
            assertTrue(failure.third.contains("success"))
        }
    }

    private fun runAction(platform: Platform): Triple<Int, String, String> {
        val stdout = Files.createTempFile(directory, "stdout-", ".txt")
        val stderr = Files.createTempFile(directory, "stderr-", ".txt")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", System.getProperty("visiontest.jar"), "press_home", "-p", platform.value, "--json",
        ).directory(directory.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        try {
            process.outputStream.close()
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "action CLI subprocess timed out")
            return Triple(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }
        }
    }
}
