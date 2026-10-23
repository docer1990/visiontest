package dev.visiontest.cli

import dev.visiontest.cli.commands.FlowCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FlowCommandTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `all input sources preflight every step without creating components`() {
        val file = directory.resolve("flow.txt")
        Files.writeString(file, "press_home\nunknown")
        val sources = listOf(listOf("--step", "press_home", "--step", "unknown"),
            listOf("--file", file.toString()), listOf("--stdin"))
        for (platform in Platform.entries) for (source in sources) {
            val components = lazy<ComponentHolder> { error("components created") }
            var result: CliResult? = null
            FlowCommand(components, runner = { result = executeCliResultCommand(it) },
                readStdin = { "press_home\nunknown" })
                .parse(listOf("-p", platform.value, "--json") + source)
            assertEquals(2, result?.exitCode)
            assertNull(result?.stdout)
            assertNotNull(result?.stderr)
            assertFalse(components.isInitialized())
        }
    }
    @Test
    fun `invalid later screenshot paths from file and stdin reject before device access`() {
        val input = "press_home\nscreenshot --output 'invalid\u0000.png'"
        val file = directory.resolve("invalid-path.txt")
        Files.writeString(file, input)
        for (platform in Platform.entries) for (source in listOf(listOf("--file", file.toString()),
            listOf("--stdin"))) FlowObservationFixture(platform).use { fixture ->
            var result: CliResult? = null
            FlowCommand(fixture.components, runner = { result = executeCliResultCommand(it) }, readStdin = { input })
                .parse(listOf("-p", platform.value) + source)
            assertEquals(2, result?.exitCode)
            assertNull(result?.stdout)
            assertNotNull(result?.stderr)
            assertFalse(fixture.components.isInitialized())
            assertEquals(emptyList(), fixture.calls)
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun `screenshot host path syntax accepts spaces relative absolute and default paths`() {
        for (platform in Platform.entries) for (line in listOf("screenshot", "screenshot --output ''",
            "screenshot --output '  '", "screenshot --output 'relative folder/shot.png'",
            "screenshot --output '${directory.resolve("absolute shot.png")}'")) {
            val components = lazy<ComponentHolder> { error("components created") }
            FlowPreparation.prepare(platform, listOf("press_home", line), components)
            assertFalse(components.isInitialized())
        }
    }

    @Test
    fun `invalid flow budgets fail preflight without stdout or device components`() {
        for (option in listOf("--timeout", "--step-timeout")) for (value in listOf("0", "-1", "3600001")) {
            val components = lazy<ComponentHolder> { error("components created") }
            var result: CliResult? = null
            FlowCommand(components, runner = { result = executeCliResultCommand(it) })
                .parse(listOf("-p", "android", "--step", "press_home", option, value, "--json"))
            assertEquals(2, result?.exitCode)
            assertNull(result?.stdout)
            assertNotNull(result?.stderr)
            assertFalse(components.isInitialized())
        }
    }

}
