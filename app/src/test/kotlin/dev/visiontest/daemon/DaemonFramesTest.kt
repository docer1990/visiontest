package dev.visiontest.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DaemonFramesTest {
    @Test fun `round trip preserves unicode and reads multiple frames`() {
        val value = buildJsonObject { put("text", "😀文字".repeat(100)) }
        val out = ByteArrayOutputStream()
        repeat(2) { DaemonFrames.write(out, value) }
        val input = ByteArrayInputStream(out.toByteArray())
        repeat(2) { assertEquals(value, DaemonFrames.read(input)) }
        assertNull(DaemonFrames.read(input))
    }

    @Test fun `invalid sizes fail before reading payload`() {
        listOf(-1, 0, DaemonFrames.MAX_FRAME_BYTES + 1).forEach { length ->
            val out = ByteArrayOutputStream()
            DataOutputStream(out).writeInt(length)
            assertFailsWith<IOException> { DaemonFrames.read(ByteArrayInputStream(out.toByteArray())) }
        }
    }

    @Test fun `truncated headers and payloads fail`() {
        listOf(byteArrayOf(0), byteArrayOf(0, 0, 0), byteArrayOf(0, 0, 0, 3, 123)).forEach {
            assertFailsWith<IOException> { DaemonFrames.read(ByteArrayInputStream(it)) }
        }
    }

    @Test fun `malformed utf8 json and nonobjects fail`() {
        listOf(byteArrayOf(0xc3.toByte(), 0x28), "[]".toByteArray(), "bad".toByteArray()).forEach {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply { writeInt(it.size); write(it) }
            assertFailsWith<IOException> { DaemonFrames.read(ByteArrayInputStream(out.toByteArray())) }
        }
    }

    @Test fun `oversize output frames fail`() {
        assertFailsWith<IOException> {
            val value = buildJsonObject { put("x", "a".repeat(DaemonFrames.MAX_FRAME_BYTES)) }
            DaemonFrames.write(ByteArrayOutputStream(), value)
        }
    }
    @Test fun `unpaired surrogate output is rejected instead of silently replaced`() {
        val value = buildJsonObject { put("text", "\uD800") }
        assertFailsWith<IOException> { DaemonFrames.write(ByteArrayOutputStream(), value) }
    }

    @Test fun `nonstandard json primitives and strings are rejected`() {
        listOf(
            "{\"x\":bare}", "{\"x\":01}", "{\"x\":\"literal\nnewline\"}", "{\"x\":TRUE}",
            "{\"x\":False}", "{\"x\":NULL}", "{\"x\":NaN}", "{\"x\":Infinity}",
            "{\"x\":+1}", "{\"x\":-01}", "{\"x\":.1}", "{\"x\":1.}", "{\"x\":1e}",
            "{\"x\":1e+}", "{\"x\":truefalse}", "{\"x\":0x10}",
        ).forEach { text -> assertFailsWith<IOException>(text) { readTextFrame(text) } }
    }

    @Test fun `invalid json structure escapes and whitespace are rejected`() {
        listOf(
            "{x:1}", "{\"x\":1,}", "{\"x\":[1,]}", "{\"x\":1}{}", "{\"x\":1 // comment\n}",
            "{\"x\":/* comment */1}", "{\"x\":'single'}", "{\"x\":1\u000b}", "{\"x\":1\u00a0}",
            "{\"x\":\"\\v\"}", "{\"x\":\"\\uXYZ0\"}", "{\"x\":\"\\u123\"}", "{\"x\":\"\u0000\"}",
        ).forEach { text -> assertFailsWith<IOException>(text) { readTextFrame(text) } }
    }

    @Test fun `standard json escapes unicode numbers and whitespace remain valid`() {
        listOf(
            " \r\n\t{\"x\": [true, false, null, {}, []]} \t",
            "{\"x\": [0,-0,1,-123,0.1,-0.1,1e9,1E-9,1e+9,1e9999]}",
            "{\"x\":\"😀文字\\u0000\\uD83D\\uDE00\\u00aF\"}",
            "{\"x\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"}",
        ).forEach { text -> assertEquals(setOf("x"), readTextFrame(text)!!.keys) }
    }

    @Test fun `excessive nesting fails as an io error`() {
        val text = "{\"x\":" + "[".repeat(512) + "0" + "]".repeat(512) + "}"
        assertFailsWith<IOException> { readTextFrame(text) }
    }


    @Test fun `nesting bound accepts its exact limit`() {
        val arrays = DaemonJsonSyntax.MAX_NESTING - 1
        val accepted = "{\"x\":" + "[".repeat(arrays) + "0" + "]".repeat(arrays) + "}"
        assertEquals(setOf("x"), readTextFrame(accepted)!!.keys)
        val rejected = "{\"x\":" + "[".repeat(arrays + 1) + "0" + "]".repeat(arrays + 1) + "}"
        assertFailsWith<IOException> { readTextFrame(rejected) }
    }

    @Test fun `every unescaped json control character is rejected`() {
        (0..31).forEach { code ->
            assertFailsWith<IOException>("Control character $code") { readTextFrame("{\"x\":\"${code.toChar()}\"}") }
        }
    }

    @Test fun `writer rejects nonstandard numeric primitives`() {
        val value = buildJsonObject { put("x", JsonPrimitive(Double.NaN)) }
        assertFailsWith<IOException> { DaemonFrames.write(ByteArrayOutputStream(), value) }
    }

    private fun readTextFrame(text: String): kotlinx.serialization.json.JsonObject? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply { writeInt(bytes.size); write(bytes) }
        return DaemonFrames.read(ByteArrayInputStream(output.toByteArray()))
    }

}
