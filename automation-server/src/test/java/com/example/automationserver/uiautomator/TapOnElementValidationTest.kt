package com.example.automationserver.uiautomator

import com.google.gson.JsonParser
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TapOnElementValidationTest {

    @Test
    fun `accepts an in-range integer JSON number for timeout`() {
        assertEquals(1_000, parseTapOnElementTimeoutMs(JsonParser.parseString("1000")))
    }

    @Test
    fun `rejects decimal JSON timeout even when numerically whole`() {
        assertFailsWith<IllegalArgumentException> {
            parseTapOnElementTimeoutMs(JsonParser.parseString("1.0"))
        }
    }

    @Test
    fun `rejects scientific JSON timeout`() {
        assertFailsWith<IllegalArgumentException> {
            parseTapOnElementTimeoutMs(JsonParser.parseString("1e3"))
        }
    }

    @Test
    fun `rejects a blank supplied selector even with another valid selector`() {
        assertFailsWith<IllegalArgumentException> {
            validateTapOnElementSelectors(text = "Save", resourceId = " ")
        }
    }

    @Test
    fun `accepts nonblank selectors`() {
        validateTapOnElementSelectors(text = "Save", contentDescription = "Save changes")
    }
}
