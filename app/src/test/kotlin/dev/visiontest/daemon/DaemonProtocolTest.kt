package dev.visiontest.daemon

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class DaemonProtocolTest {
    private val identity = DaemonIdentity(1, "1.0.1", "a".repeat(64))

    @Test fun `all message kinds round trip`() {
        val messages = listOf(
            DaemonMessage.Hello(identity), DaemonMessage.Ready(identity),
            DaemonMessage.Execute("r", listOf("--platform", "ios"), "/tmp", mapOf("TERM" to "dumb"), emptyMap()),
            DaemonMessage.StdinRequest("r"), DaemonMessage.Input("r", "YWJj", true),
            DaemonMessage.Output("r", "stdout", "YWJj"), DaemonMessage.Output("r", "stderr", "YWJj"),
            DaemonMessage.Completion("r", 5), DaemonMessage.Cancel("r"),
            DaemonMessage.Status(), DaemonMessage.Stop(),
        )
        messages.forEach { assertEquals(it, DaemonProtocol.decode(DaemonProtocol.encode(it))) }
    }

    @Test fun `execution requires exact build identity but control accepts same protocol`() {
        val different = identity.copy(build = "b".repeat(64))
        assertFailsWith<IOException> { DaemonProtocol.requireCompatible(identity, different, execution = true) }
        DaemonProtocol.requireCompatible(identity, different, execution = false)
        assertFailsWith<IOException> { DaemonProtocol.requireCompatible(identity, different.copy(protocol = 2), false) }
        DaemonProtocol.requireCompatible(identity, identity, true)
    }

    @Test fun `invalid fields and unknown message kinds fail`() {
        listOf(
            "{}", "{\"kind\":\"mystery\"}", "{\"kind\":\"cancel\",\"requestId\":5}",
            "{\"kind\":\"completion\",\"requestId\":\"r\",\"exitCode\":\"0\"}",
            "{\"kind\":\"input\",\"requestId\":\"r\",\"data\":\"!\",\"eof\":true}",
            "{\"kind\":\"hello\",\"protocol\":1,\"appVersion\":\"1\",\"build\":\"bad\"}",
        ).forEach { assertFailsWith<IOException> { DaemonProtocol.decode(Json.parseToJsonElement(it).jsonObject) } }
    }

    @Test fun `context must be absolute and bounded`() {
        listOf(
            DaemonMessage.Execute("r", emptyList(), "relative", emptyMap(), emptyMap()),
            DaemonMessage.Execute("", emptyList(), "/tmp", emptyMap(), emptyMap()),
            DaemonMessage.Execute(
                "r", listOf("x".repeat(DaemonFrames.MAX_FRAME_BYTES)), "/tmp", emptyMap(), emptyMap(),
            ),
        ).forEach { assertFailsWith<IOException> { DaemonProtocol.encode(it) } }
    }

    @Test fun `output chunks preserve exact bytes and enforce total output limit`() {
        val original = "😀文字".repeat(20000).toByteArray()
        val chunks = DaemonProtocol.outputChunks("r", "stdout", original)
        assertTrue(chunks.all { java.util.Base64.getDecoder().decode(it.data).size <= DaemonProtocol.MAX_CHUNK_BYTES })
        val restored = chunks.flatMap { java.util.Base64.getDecoder().decode(it.data).toList() }.toByteArray()
        assertContentEquals(original, restored)
        val budget = DaemonOutputBudget(3)
        budget.accept(3)
        val error = assertFailsWith<IOException> { budget.accept(1) }
        assertTrue(error.message!!.contains("uncertain"))
        assertFailsWith<IOException> {
            val payload = java.util.Base64.getEncoder().encodeToString(ByteArray(65537))
            DaemonProtocol.encode(DaemonMessage.Output("r", "stdout", payload))
        }
    }
    @Test fun `output cannot impersonate a control message`() {
        assertFailsWith<IOException> {
            DaemonProtocol.encode(DaemonMessage.Output("r", "status", "YWJj"))
        }
    }

    @Test fun `invalid process environment is rejected before dispatch`() {
        listOf(mapOf("" to "x"), mapOf("A=B" to "x"), mapOf("A" to "x\u0000y")).forEach { environment ->
            assertFailsWith<IOException> {
                DaemonProtocol.encode(DaemonMessage.Execute("r", emptyList(), "/tmp", environment, emptyMap()))
            }
        }
    }

    @Test fun `optional caller terminal dimensions round trip`() {
        val message = DaemonMessage.Execute("r", emptyList(), "/tmp", emptyMap(), emptyMap(),
            dev.visiontest.cli.CliTerminalDimensions(40, 24, true))
        assertEquals(message, DaemonProtocol.decode(DaemonProtocol.encode(message)))
        val without = message.copy(terminalDimensions = null)
        assertEquals(without, DaemonProtocol.decode(DaemonProtocol.encode(without)))
    }

    @Test fun `caller routing property round trips without modifying its environment`() {
        val message = DaemonMessage.Execute("r", emptyList(), "/tmp", emptyMap(), emptyMap(),
            routingProperties = mapOf("ANDROID_ADB_SERVER_PORT" to "5038"))
        assertEquals(message, DaemonProtocol.decode(DaemonProtocol.encode(message)))
        assertEquals(emptyMap(), message.environment)
    }

    @Test fun `invalid caller terminal dimensions are rejected before execution`() {
        for (terminal in listOf("null", "[]", "{}", "{\"width\":0,\"height\":24}",
            "{\"width\":40,\"height\":10001}", "{\"width\":\"40\",\"height\":24}",
            "{\"width\":40,\"height\":24,\"interactive\":\"true\"}")) {
            val message = DaemonProtocol.encode(DaemonMessage.Execute("r", emptyList(), "/tmp", emptyMap(), emptyMap()))
            val invalid = kotlinx.serialization.json.JsonObject(message +
                ("terminal" to Json.parseToJsonElement(terminal)))
            assertFailsWith<IOException> { DaemonProtocol.decode(invalid) }
        }
    }

}
