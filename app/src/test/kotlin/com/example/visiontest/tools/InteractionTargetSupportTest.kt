package com.example.visiontest.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InteractionTargetSupportTest {
    @Test
    fun `key input requires exactly one supported form`() {
        assertEquals(KeyInput.KeyCode(66), validateKeyInput(66, null))
        assertEquals(KeyInput.Action("enter"), validateKeyInput(null, "ENTER"))
        assertFailsWith<IllegalArgumentException> { validateKeyInput(null, null) }
        assertFailsWith<IllegalArgumentException> { validateKeyInput(66, "enter") }
        assertFailsWith<IllegalArgumentException> { validateKeyInput(null, "space") }
    }

    @Test
    fun `gesture target accepts coordinates or selectors`() {
        assertEquals(InteractionTarget.Coordinates(10, 20), validateInteractionTarget(10, 20, emptyList(), 0, null))
        assertEquals(
            InteractionTarget.Element(1_500),
            validateInteractionTarget(null, null, listOf("text" to "Menu"), 1, 1_500),
        )
    }

    @Test
    fun `gesture target rejects partial mixed blank and invalid timeout inputs`() {
        assertFailsWith<IllegalArgumentException> { validateInteractionTarget(10, null, emptyList(), 0, null) }
        assertFailsWith<IllegalArgumentException> {
            validateInteractionTarget(10, 20, listOf("text" to "Menu"), 1, null)
        }
        assertFailsWith<IllegalArgumentException> {
            validateInteractionTarget(null, null, listOf("text" to " "), 1, null)
        }
        assertFailsWith<IllegalArgumentException> {
            validateInteractionTarget(null, null, listOf("text" to "Menu"), 1, 30_001)
        }
    }

    @Test
    fun `targeted input resolves selector timeout and preserves focused mode`() {
        assertNull(validateTargetedInput(emptyList(), 0, null))
        assertEquals(10_000, validateTargetedInput(listOf("resourceId" to "field"), 1, null))
        assertFailsWith<IllegalArgumentException> { validateTargetedInput(emptyList(), 0, 500) }
        assertFailsWith<IllegalArgumentException> {
            validateTargetedInput(listOf("resourceId" to ""), 1, null)
        }
    }
}
