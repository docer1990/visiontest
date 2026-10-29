package dev.visiontest.cli

import dev.visiontest.tools.ScreenshotSaver
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliRequestContextTest {
    @TempDir lateinit var root: Path
    private val executor = CliRequestExecutor()

    @Test
    fun `same flow filename reads each caller directory`() {
        val holder = mockk<ComponentHolder>(relaxed = true)
        coEvery { holder.isServerRunning(Platform.Android) } returns true
        coEvery { holder.androidAutomationRegistrar.pressHome() } returns
            """{"result":{"success":true}}"""
        listOf("first" to "press_home", "second" to "unknown_command").forEach { (name, input) ->
            val dir = Files.createDirectory(root.resolve(name))
            Files.writeString(dir.resolve("flow.txt"), input)
            val result = executor.execute(arrayOf("flow", "--platform", "android", "--file", "flow.txt"),
                CliExecutionContext(workingDirectory = dir), { holder })
            assertEquals(if (name == "first") 0 else 2, result.exitCode, result.stderr)
        }
    }

    @Test
    fun `argument files and nested argument files resolve from caller directory`() {
        Files.writeString(root.resolve("outer.args"), "@inner.args")
        Files.writeString(root.resolve("inner.args"), "--version")
        val result = executor.execute(arrayOf("@outer.args"), CliExecutionContext(workingDirectory = root))
        assertEquals(0, result.exitCode, result.stderr)
        assertContains(result.stdout!!, "visiontest")
    }

    @Test
    fun `screenshots and trace use caller directory`() {
        val holder = mockk<ComponentHolder>(relaxed = true)
        coEvery { holder.isServerRunning(Platform.Android) } returns true
        coEvery { holder.androidAutomationRegistrar.pressHome() } coAnswers {
            val saver = ScreenshotSaver("Android", "android_screenshot", "APK")
            assertEquals(root.resolve("capture.png"), saver.resolveScreenshotPath("capture.png").toPath())
            assertEquals(root.resolve("screenshots"), saver.resolveScreenshotPath(null).toPath().parent)
            "ok"
        }
        val traceName = "request-${root.fileName}.jsonl"
        try {
            val result = executor.execute(
                arrayOf("--trace-performance", traceName, "press_home", "--platform", "android"),
                CliExecutionContext(workingDirectory = root), { holder })
            assertEquals(0, result.exitCode, result.stderr)
            assertTrue(Files.exists(root.resolve(traceName)))
        } finally {
            Files.deleteIfExists(Path.of(traceName))
        }
    }

    @Test
    fun `invalid ordinary command never consumes stdin`() {
        var consumed = false
        val result = executor.execute(arrayOf("press_home"), CliExecutionContext(readStdin = {
            consumed = true
            error("stdin must remain unread")
        }))
        assertEquals(2, result.exitCode)
        assertFalse(consumed)
    }
}
