package com.example.visiontest.cli

import com.example.visiontest.cli.commands.WaitForElementCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WaitOutputTest {
    private fun invoke(components: Lazy<ComponentHolder>, args: List<String>): CliResult {
        var result: CliResult? = null
        val parsed = executeCliCommand {
            WaitForElementCommand(components) { result = executeCliCommand(it) }.parse(args)
            ""
        }
        return result ?: parsed
    }

    private fun args(platform: Platform, gone: Boolean, json: Boolean) =
        listOf("-p", platform.value, "--text", "Ready", "--timeout", "1") +
            (if (gone) listOf("--gone") else emptyList()) +
            (if (json) listOf("--json") else emptyList())

    @Test
    fun `both conditions have symmetric text and unwrapped additive json`() {
        for (platform in Platform.entries) for (gone in listOf(false, true)) {
            FlowObservationFixture(platform).use { fixture ->
                val payload = """{"found":${!gone},"extra":{"items":[null,3]},"text":"Ready"}"""
                fixture.respond = { MockResponse().setBody("""{"jsonrpc":"2.0","id":17,"result":$payload}""") }
                val condition = if (gone) "gone" else "appeared"
                assertEquals(CliResult(0, "Element $condition.", null),
                    invoke(fixture.components, args(platform, gone, false)))
                val expected = payload.dropLast(1) + """, "condition":"$condition","satisfied":true}"""
                val result = invoke(fixture.components, args(platform, gone, true))
                assertEquals(0, result.exitCode, result.stderr)
                assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(assertNotNull(result.stdout)))
                assertEquals(listOf("ui.findElement", "ui.findElement"), fixture.calls)
            }
        }
    }

    @Test
    fun `matching returned operation error retains exit zero and error field`() {
        for (platform in Platform.entries) for (gone in listOf(false, true)) {
            FlowObservationFixture(platform).use { fixture ->
                fixture.respond = { MockResponse().setBody("""{"result":{"found":${!gone},"error":"blocked"}}""") }
                val result = invoke(fixture.components, args(platform, gone, true))
                assertEquals(0, result.exitCode, result.stderr)
                val value = Json.parseToJsonElement(assertNotNull(result.stdout)).jsonObject
                assertEquals("blocked", value.getValue("error").jsonPrimitive.content)
                assertEquals("true", value.getValue("satisfied").toString())
                assertEquals(CliResult(0, "Element ${if (gone) "gone" else "appeared"}.", null),
                    invoke(fixture.components, args(platform, gone, false)))
            }
        }
    }

    @Test
    fun `invalid final results fail without partial output or replay`() {
        for (platform in Platform.entries) for (gone in listOf(false, true)) {
            FlowObservationFixture(platform).use { fixture ->
                val invalid = listOf("{}", "not json", "[]", """{"result":[]}""",
                    """{"result":{}}""", """{"result":{"found":"false"}}""",
                    """{"error":{"code":-1,"message":"blocked"}}""",
                    """{"result":{"found":${!gone},"error":null}}""",
                    """{"result":{"found":${!gone},"bounds":{}}}""",
                    """{"result":{"found":${!gone},"isEnabled":"true"}}""",
                    """{"result":{"found":${!gone},"${if (platform == Platform.Ios) "value" else "isClickable"}":3}}""")
                for (response in invalid) for (json in listOf(false, true)) {
                    fixture.respond = { MockResponse().setBody(response) }
                    val result = invoke(fixture.components, args(platform, gone, json))
                    assertEquals(1, result.exitCode, "$platform $response")
                    assertNull(result.stdout)
                    assertNotNull(result.stderr)
                }
                assertEquals(invalid.size * 2, fixture.calls.size)
            }
        }
    }

    @Test
    fun `timeout and stopped server retain exits in both modes`() {
        for (platform in Platform.entries) for (gone in listOf(false, true)) {
            FlowObservationFixture(platform).use { fixture ->
                fixture.respond = { MockResponse().setBody("""{"result":{"found":$gone}}""") }
                for (json in listOf(false, true)) {
                    val result = invoke(fixture.components, args(platform, gone, json))
                    assertEquals(1, result.exitCode)
                    assertNull(result.stdout)
                    assertNotNull(result.stderr)
                }
                fixture.server.shutdown()
                for (json in listOf(false, true)) {
                    val result = invoke(fixture.components, args(platform, gone, json))
                    assertEquals(3, result.exitCode)
                    assertNull(result.stdout)
                }
            }
        }
    }

    @Test
    fun `nonmatching polls retain found-only validation`() {
        for (platform in Platform.entries) for (gone in listOf(false, true)) {
            FlowObservationFixture(platform).use { fixture ->
                var polls = 0
                fixture.respond = {
                    polls++
                    MockResponse().setBody(if (polls == 1) {
                        """{"result":{"found":$gone,"bounds":null,"error":"blocked"}}"""
                    } else """{"result":{"found":${!gone}}}""")
                }
                val options = args(platform, gone, true).map { if (it == "1") "1500" else it }
                assertEquals(0, invoke(fixture.components, options).exitCode)
                assertEquals(2, polls)
            }
        }
    }

    @Test
    fun `http failures never satisfy waits or replay requests`() {
        for (platform in Platform.entries) for (gone in listOf(false, true)) {
            FlowObservationFixture(platform).use { fixture ->
                fixture.respond = { MockResponse().setResponseCode(503).setBody("unavailable") }
                for (json in listOf(false, true)) {
                    val result = invoke(fixture.components, args(platform, gone, json))
                    assertEquals(1, result.exitCode)
                    assertNull(result.stdout)
                    assertNotNull(result.stderr)
                }
                assertEquals(2, fixture.calls.size)
            }
        }
    }

    @Test
    fun `invalid input and flow json reject before component creation`() {
        val components = lazy<ComponentHolder> { error("Unexpected component creation") }
        for (platform in Platform.entries) {
            for (options in listOf(emptyList(), listOf("--text", ""), listOf("--bundle-id", "app"),
                listOf("--text", "Ready", "--timeout", "0"), listOf("--text", "Ready", "--timeout", "30001"),
                listOf("--text", "Ready", "--bundle-id", ""))) {
                val result = invoke(components, listOf("-p", platform.value, "--json") + options)
                assertEquals(2, result.exitCode)
                assertNull(result.stdout)
            }
            val flow = executeCliCommand {
                FlowPreparation.prepare(platform, listOf("wait_for_element --text Ready --json"), components)
                ""
            }
            assertEquals(2, flow.exitCode)
        }
        assertFalse(components.isInitialized())
    }

    @Test
    fun `selectors and ios scope reach each poll unchanged`() {
        for (platform in Platform.entries) FlowObservationFixture(platform).use { fixture ->
            fixture.respond = { MockResponse().setBody("""{"result":{"found":true}}""") }
            val selectors = listOf("--text-contains", "ead", "--resource-id", "ready", "--class-name", "Button",
                "--content-description", "Ready label") +
                if (platform == Platform.Ios) listOf("--bundle-id", "test.app") else emptyList()
            assertEquals(0, invoke(fixture.components, args(platform, false, true) + selectors).exitCode)
            val params = fixture.requests.single().getValue("params").jsonObject
            for ((key, value) in mapOf("text" to "Ready", "textContains" to "ead", "resourceId" to "ready",
                "className" to "Button", "contentDescription" to "Ready label")) {
                assertEquals(value, params.getValue(key).jsonPrimitive.content)
            }
            if (platform == Platform.Ios) assertEquals("test.app", params.getValue("bundleId").jsonPrimitive.content)
        }
    }
}
