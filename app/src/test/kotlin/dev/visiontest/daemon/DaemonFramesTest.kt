package dev.visiontest.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
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

}
