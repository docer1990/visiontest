package com.example.visiontest.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidRecordingCapabilitiesTest {
    @Test
    fun `a limited recorder rejects a budget one millisecond above its limit`() {
        val capabilities = AndroidRecordingCapabilities.parse(
            "--time-limit TIME\nSet the maximum recording time, in seconds. Maximum / default is 180."
        )
        assertEquals(180L, capabilities.durationSeconds(180_000))
        val failure = assertFailsWith<RecordingException> { capabilities.durationSeconds(180_001) }
        assertTrue(failure.message.orEmpty().contains("181"))
        assertTrue(failure.message.orEmpty().contains("180"))
    }

    @Test
    fun `unlimited support still selects a finite rounded duration`() {
        val capabilities = AndroidRecordingCapabilities.parse(
            "--time-limit TIME\nDefault is 180. Set to 0 to remove the time limit."
        )
        assertEquals(181L, capabilities.durationSeconds(180_001))
        assertEquals(150L, capabilities.durationSeconds(150_000))
        assertEquals(155L, capabilities.durationSeconds(155_000))
    }

    @Test
    fun `default alone and unknown help do not establish a supported bound`() {
        listOf("", "screenrecord help", "--time-limit TIME\nDefault is 180.").forEach { help ->
            assertFailsWith<RecordingException> { AndroidRecordingCapabilities.parse(help).durationSeconds(150_000) }
        }
    }

    @Test
    fun `malformed bounds and overflow are rejected`() {
        listOf("-1", "0", "180x", "1.5", "99999999999999999999999").forEach { bound ->
            assertFailsWith<RecordingException> {
                AndroidRecordingCapabilities.parse("--time-limit TIME\nMaximum / default is $bound.")
                    .durationSeconds(1000)
            }
        }
        val capabilities = AndroidRecordingCapabilities.parse(
            "--time-limit TIME\nDefault is 180. Set to 0 to remove the time limit."
        )
        listOf(0L, -1L, Long.MAX_VALUE).forEach { duration ->
            assertFailsWith<RecordingException> { capabilities.durationSeconds(duration) }
        }
    }
}
