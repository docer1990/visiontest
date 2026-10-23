package dev.visiontest.cli

import dev.visiontest.cli.commands.ClearTextCommand
import dev.visiontest.cli.commands.DismissKeyboardCommand
import dev.visiontest.cli.commands.DoubleTapCommand
import dev.visiontest.cli.commands.HandleAlertCommand
import dev.visiontest.cli.commands.InputTextCommand
import dev.visiontest.cli.commands.LongPressCommand
import dev.visiontest.cli.commands.PressBackCommand
import dev.visiontest.cli.commands.PressHomeCommand
import dev.visiontest.cli.commands.PressKeyCommand
import dev.visiontest.cli.commands.SwipeCommand
import dev.visiontest.cli.commands.SwipeDirectionCommand
import dev.visiontest.cli.commands.SwipeOnElementCommand
import dev.visiontest.cli.commands.TapByCoordinatesCommand
import dev.visiontest.cli.commands.TapOnElementCommand
import com.github.ajalt.clikt.core.CliktCommand
import okhttp3.mockwebserver.MockResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal data class ActionJsonCase(
    val name: String,
    val factory: (Lazy<ComponentHolder>, CliCommandRunner) -> CliktCommand,
    val args: List<String>,
    val platforms: List<Platform> = Platform.entries,
) {
    override fun toString() = name
}

class ActionJsonOutputTest {
    companion object {
        @JvmStatic
        internal fun actions() = listOf(
            ActionJsonCase("tap_by_coordinates", ::TapByCoordinatesCommand, listOf("1", "2")),
            ActionJsonCase("tap_on_element", ::TapOnElementCommand, listOf("--text", "Login")),
            ActionJsonCase("input_text", ::InputTextCommand, listOf("hello")),
            ActionJsonCase("long_press", ::LongPressCommand, listOf("--x", "1", "--y", "2")),
            ActionJsonCase("double_tap", ::DoubleTapCommand, listOf("--text", "Login")),
            ActionJsonCase("swipe_direction", ::SwipeDirectionCommand, listOf("left")),
            ActionJsonCase("swipe", ::SwipeCommand, listOf("1", "2", "3", "4")),
            ActionJsonCase("swipe_on_element", ::SwipeOnElementCommand, listOf("left", "--text", "Carousel")),
            ActionJsonCase("press_home", ::PressHomeCommand, emptyList()),
            ActionJsonCase("press_back", ::PressBackCommand, emptyList(), listOf(Platform.Android)),
            ActionJsonCase("press_key", ::PressKeyCommand, listOf("enter"), listOf(Platform.Android)),
            ActionJsonCase("clear_text", ::ClearTextCommand, emptyList(), listOf(Platform.Android)),
            ActionJsonCase("dismiss_keyboard", ::DismissKeyboardCommand, emptyList(), listOf(Platform.Ios)),
            ActionJsonCase("handle_alert", ::HandleAlertCommand, listOf("accept"), listOf(Platform.Ios)),
        )
    }

    private fun invoke(action: ActionJsonCase, components: Lazy<ComponentHolder>, args: List<String>): CliResult {
        var result: CliResult? = null
        val parsed = executeCliCommand {
            action.factory(components) { result = executeCliCommand(it) }.parse(args)
            ""
        }
        return result ?: parsed
    }

    @Test
    internal fun `json preserves success and returned failures for every supported pair`() {
        for (action in actions()) for (platform in action.platforms) assertActionResults(action, platform)
    }

    private fun assertActionResults(action: ActionJsonCase, platform: Platform) {
        FlowObservationFixture(platform).use { fixture ->
            for (payload in listOf(
                """{"success":true}""",
                """{"success":true,"extra":{"label":"a\\b","items":[null,3]}}""",
                """{"success":false}""", """{"success":false,"error":"blocked"}""",
            )) {
                fixture.respond = { MockResponse().setBody("""{"jsonrpc":"2.0","id":17,"result":$payload}""") }
                val result = invoke(action, fixture.components, listOf("-p", platform.value, "--json") + action.args)
                assertEquals(CliResult(0, payload, null), result, "$action $platform")
            }
            assertEquals(4, fixture.calls.size, "Each action must execute once")
        }
    }

    @Test
    internal fun `json preserves complete returned RPC errors`() = actions().forEach { action ->
        val error = """{"code":-32601,"message":"Unknown method","data":{"detail":null},"extra":true}"""
        for (platform in action.platforms) FlowObservationFixture(platform).use { fixture ->
            fixture.respond = { MockResponse().setBody("""{"jsonrpc":"2.0","id":17,"error":$error}""") }
            val result = invoke(action, fixture.components, listOf("-p", platform.value, "--json") + action.args)
            assertEquals(CliResult(0, """{"error":$error}""", null), result)
            assertEquals(1, fixture.calls.size)
        }
    }

    @Test
    internal fun `invalid json results fail without partial output or replay`() {
        for (action in actions()) for (platform in action.platforms) assertInvalidResults(action, platform)
    }

    private fun assertInvalidResults(action: ActionJsonCase, platform: Platform) {
        val invalid = listOf(
            "{}", "not json", "[]", "null", """{"result":[]}""",
            """{"result":{}}""", """{"result":{"success":"true"}}""",
            """{"result":{"success":null}}""", """{"result":{"success":1}}""",
            """{"result":{"success":true,"error":null}}""",
            """{"result":{"success":false,"error":3}}""",
            """{"result":{"success":true},"error":{"code":-1,"message":"bad"}}""",
            """{"error":{"code":"-1","message":"bad"}}""",
        )
        FlowObservationFixture(platform).use { fixture ->
            for (response in invalid) {
                fixture.respond = { MockResponse().setBody(response) }
                val result = invoke(action, fixture.components, listOf("-p", platform.value, "--json") + action.args)
                assertEquals(1, result.exitCode, "$action $platform $response")
                assertNull(result.stdout)
                assertNotNull(result.stderr)
            }
            assertEquals(invalid.size, fixture.calls.size)
        }
    }

    @Test
    internal fun `default output remains unchanged`() = actions().forEach { action ->
        val response = """{"jsonrpc":"2.0","id":17,"result":{"success":true}}"""
        for (platform in action.platforms) FlowObservationFixture(platform).use { fixture ->
            fixture.respond = { MockResponse().setBody(response) }
            val result = invoke(action, fixture.components, listOf("-p", platform.value) + action.args)
            assertEquals(0, result.exitCode, result.stderr)
            assertEquals(response, result.stdout)
            assertNull(result.stderr)
        }
    }

    @Test
    internal fun `json help and platform checks do not initialize components`() = actions().forEach { action ->
        val components = lazy<ComponentHolder> { error("Unexpected components") }
        assertTrue(assertNotNull(action.factory(components) {}.getFormattedHelp()).contains("--json"))
        for (platform in Platform.entries - action.platforms.toSet()) {
            val result = invoke(action, components, listOf("-p", platform.value, "--json") + action.args)
            assertEquals(5, result.exitCode)
            assertNull(result.stdout)
        }
        assertFalse(components.isInitialized())
    }

    @Test
    internal fun `flows reject action json before creating components`() = actions().forEach { action ->
        val components = lazy<ComponentHolder> { error("Unexpected components") }
        for (platform in action.platforms) {
            val result = executeCliCommand {
                val step = (listOf(action.name) + action.args + "--json").joinToString(" ")
                FlowPreparation.prepare(platform, listOf(step), components)
                ""
            }
            assertEquals(2, result.exitCode)
            assertNull(result.stdout)
        }
        assertFalse(components.isInitialized())
    }

    @Test
    fun `invalid action arguments with json fail before components are created`() {
        val components = lazy<ComponentHolder> { error("Unexpected components") }
        val cases = listOf(
            actions()[0] to listOf("wrong", "2"), actions()[1] to emptyList(),
            actions()[2] to listOf("hello", "--timeout", "1"), actions()[3] to listOf("--x", "1"),
            actions()[4] to listOf("--x", "1", "--y", "2", "--text", "Login"),
            actions()[5] to listOf("diagonal"), actions()[6] to listOf("1", "2", "3", "4", "--steps", "0"),
            actions()[7] to listOf("left"), actions()[10] to listOf("-1"), actions()[13] to listOf("wrong"),
        )
        for ((action, args) in cases) for (platform in action.platforms) {
            val result = invoke(action, components, listOf("-p", platform.value, "--json") + args)
            assertEquals(2, result.exitCode, action.name)
            assertNull(result.stdout)
        }
        assertFalse(components.isInitialized())
    }

    @Test
    fun `default element tap preserves failure prose`() {
        for (platform in Platform.entries) FlowObservationFixture(platform).use { fixture ->
            fixture.respond = { MockResponse().setBody("""{"result":{"success":false,"error":"blocked"}}""") }
            val action = actions()[1]
            val result = invoke(action, fixture.components, listOf("-p", platform.value) + action.args)
            assertEquals(CliResult(1, null, "Element tap failed: blocked"), result)
        }
    }

    @Test
    fun `thrown server failures retain mapped exit and empty stdout`() {
        for (platform in Platform.entries) FlowObservationFixture(platform).use { fixture ->
            fixture.server.shutdown()
            val result = invoke(actions()[8], fixture.components, listOf("-p", platform.value, "--json"))
            assertEquals(3, result.exitCode)
            assertNull(result.stdout)
            assertNotNull(result.stderr)
        }
    }
}
