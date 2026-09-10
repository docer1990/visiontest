package com.example.automationserver.uiautomator

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElementInteractionWaitTest {

    @Test
    fun `gesture waits at five hundred millisecond cadence until ready`() {
        val clock = FakeClock()
        var lookups = 0
        var interactions = 0
        val result = waitForTargetAndInteract(
            timeoutMs = 1_000,
            description = "resourceId='menu'",
            clock = ElementInteractionClock(clock::now, clock::sleep),
            lookup = {
                lookups += 1
                if (lookups == 2) ready("menu") else null
            },
            interact = { interactions += 1 },
        )

        assertTrue(result.success)
        assertEquals(500, clock.now())
        assertEquals(1, interactions)
    }

    @Test
    fun `gesture distinguishes absent and blocked timeout`() {
        listOf(
            { null } to "not found",
            { ElementInteractionCandidate("menu", ElementInteractionReadiness.BLOCKED) } to "not actionable",
        ).forEach { (lookup, expected) ->
            val clock = FakeClock()
            val result = waitForTargetAndInteract(
                timeoutMs = 500,
                description = "resourceId='menu'",
                clock = ElementInteractionClock(clock::now, clock::sleep),
                lookup = lookup,
                interact = { error("must not interact") },
            )
            assertFalse(result.success)
            assertTrue(result.error!!.contains(expected))
        }
    }

    @Test
    fun `gesture may interact with a ready target at the deadline`() {
        val clock = FakeClock()
        var lookups = 0
        val result = waitForTargetAndInteract(
            timeoutMs = 500,
            description = "element",
            clock = ElementInteractionClock(clock::now, clock::sleep),
            lookup = {
                lookups += 1
                if (lookups == 2) ready("element") else null
            },
            interact = { },
        )

        assertTrue(result.success)
        assertEquals(500, clock.now())
    }

    @Test
    fun `targeted input uses one timeout budget for lookup and focus`() {
        val clock = FakeClock()
        var typed = false
        val result = waitForTargetFocusAndInput(
            timeoutMs = 1_000,
            description = "resourceId='name'",
            clock = ElementInteractionClock(clock::now, clock::sleep),
            operation = TargetedInputOperation(
                lookup = { if (clock.now() >= 500) ready("field") else null },
                tap = { },
                hasEditableFocus = { false },
                input = { typed = true },
            ),
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("focus"))
        assertEquals(1_000, clock.now())
        assertFalse(typed)
    }

    @Test
    fun `targeted input types after focus arrives`() {
        val clock = FakeClock()
        val events = mutableListOf<String>()
        val result = waitForTargetFocusAndInput(
            timeoutMs = 1_000,
            description = "resourceId='name'",
            clock = ElementInteractionClock(clock::now, clock::sleep),
            operation = TargetedInputOperation(
                lookup = { ready("field") },
                tap = { events += "tap" },
                hasEditableFocus = { clock.now() >= 500 },
                input = { events += "input" },
            ),
        )

        assertTrue(result.success)
        assertEquals(listOf("tap", "input"), events)
        assertEquals(500, clock.now())
    }

    private fun ready(value: String) =
        ElementInteractionCandidate(value, ElementInteractionReadiness.READY)

    private class FakeClock {
        private var elapsedMs = 0L
        fun now(): Long = elapsedMs
        fun sleep(durationMs: Long) { elapsedMs += durationMs }
    }
}
