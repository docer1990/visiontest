package com.example.automationserver.uiautomator

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElementTapWaitTest {

    @Test
    fun `taps the ready element after it was initially absent`() {
        val clock = FakeClock()
        var lookups = 0
        var taps = 0

        val result = waitAndTapElement(
            timeoutMs = 1_000,
            clock = ElementTapClock(clock::now, clock::sleep),
            operation = ElementTapOperation(lookup = {
                lookups += 1
                if (lookups == 2) ElementTapCandidate("target", enabled = true, hasVisibleBounds = true) else null
            }, tap = { taps += 1 })
        )

        assertTrue(result.success)
        assertEquals(1, taps)
        assertEquals(500, clock.now())
    }

    @Test
    fun `taps the ready element after it was initially blocked`() {
        val clock = FakeClock()
        var lookups = 0
        var taps = 0

        val result = waitAndTapElement(
            timeoutMs = 1_000,
            clock = ElementTapClock(clock::now, clock::sleep),
            operation = ElementTapOperation(lookup = {
                lookups += 1
                ElementTapCandidate("target", enabled = lookups == 2, hasVisibleBounds = true)
            }, tap = { taps += 1 })
        )

        assertTrue(result.success)
        assertEquals(1, taps)
        assertEquals(500, clock.now())
    }

    @Test
    fun `reports not found after timeout without tapping`() {
        val clock = FakeClock()
        var taps = 0

        val result = waitAndTapElement<String>(
            timeoutMs = 1_000,
            clock = ElementTapClock(clock::now, clock::sleep),
            operation = ElementTapOperation(lookup = { null }, tap = { taps += 1 })
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("Element not found"))
        assertEquals(0, taps)
        assertEquals(1_000, clock.now())
    }

    @Test
    fun `reports found but not tappable after timeout without tapping`() {
        val clock = FakeClock()
        var taps = 0

        val result = waitAndTapElement(
            timeoutMs = 1_000,
            clock = ElementTapClock(clock::now, clock::sleep),
            operation = ElementTapOperation(
                lookup = { ElementTapCandidate("target", enabled = false, hasVisibleBounds = true) },
                tap = { taps += 1 }
            )
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("Element found but not tappable"))
        assertEquals(0, taps)
    }

    @Test
    fun `does not tap when lookup throws`() {
        val clock = FakeClock()
        var taps = 0

        val result = waitAndTapElement<String>(
            timeoutMs = 1_000,
            clock = ElementTapClock(clock::now, clock::sleep),
            operation = ElementTapOperation(
                lookup = { throw IllegalStateException("lookup failed") },
                tap = { taps += 1 }
            )
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("lookup failed"))
        assertEquals(0, taps)
    }

    @Test
    fun `returns failure when tapping throws`() {
        val clock = FakeClock()
        var taps = 0

        val result = waitAndTapElement(
            timeoutMs = 1_000,
            clock = ElementTapClock(clock::now, clock::sleep),
            operation = ElementTapOperation(lookup = {
                ElementTapCandidate("target", enabled = true, hasVisibleBounds = true)
            }, tap = {
                taps += 1
                error("tap failed")
            })
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("tap failed"))
        assertEquals(1, taps)
    }

    @Test
    fun `does not tap a ready element found after the deadline`() {
        val clock = FakeClock()
        var taps = 0

        val result = waitAndTapElement(
            timeoutMs = 1_000,
            clock = ElementTapClock(clock::now, clock::sleep),
            operation = ElementTapOperation(lookup = {
                clock.sleep(1_001)
                ElementTapCandidate("target", enabled = true, hasVisibleBounds = true)
            }, tap = { taps += 1 })
        )

        assertFalse(result.success)
        assertEquals(0, taps)
    }

    @Test
    fun `taps a ready element found exactly at the deadline`() {
        val clock = FakeClock()
        var lookups = 0
        var taps = 0

        val result = waitAndTapElement(
            timeoutMs = 500,
            clock = ElementTapClock(clock::now, clock::sleep),
            operation = ElementTapOperation(lookup = {
                lookups += 1
                if (lookups == 2) ElementTapCandidate("target", enabled = true, hasVisibleBounds = true) else null
            }, tap = { taps += 1 })
        )

        assertTrue(result.success)
        assertEquals(1, taps)
        assertEquals(500, clock.now())
    }

    private class FakeClock {
        private var elapsedMs = 0L

        fun now(): Long = elapsedMs

        fun sleep(durationMs: Long) {
            elapsedMs += durationMs
        }
    }
}
