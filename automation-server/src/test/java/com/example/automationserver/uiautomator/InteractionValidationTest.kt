package com.example.automationserver.uiautomator

import android.view.KeyEvent
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class InteractionValidationTest {

    @Test
    fun `named key actions map to Android key codes`() {
        assertEquals(KeyEvent.KEYCODE_ENTER, parseNamedKeyAction("enter"))
        assertEquals(KeyEvent.KEYCODE_TAB, parseNamedKeyAction("tab"))
        assertEquals(KeyEvent.KEYCODE_DEL, parseNamedKeyAction("backspace"))
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, parseNamedKeyAction("delete"))
        assertEquals(KeyEvent.KEYCODE_ESCAPE, parseNamedKeyAction("escape"))
        assertFailsWith<IllegalArgumentException> { parseNamedKeyAction("home") }
    }

    @Test
    fun `key request accepts exactly one numeric or named key`() {
        assertEquals(66, parseKeyRequest(json("""{"keyCode":66}""")))
        assertEquals(KeyEvent.KEYCODE_ENTER, parseKeyRequest(json("""{"action":"enter"}""")))
        listOf(
            "{}",
            """{"keyCode":-1}""",
            """{"keyCode":1.5}""",
            """{"keyCode":true}""",
            """{"keyCode":66,"action":"enter"}""",
            """{"action":" "}""",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { parseKeyRequest(json(value)) }
        }
    }

    @Test
    fun `gesture request accepts coordinate or selector target`() {
        val coordinates = parseGestureRequest(json("""{"x":10,"y":20}"""))
        assertEquals(NativeGestureTarget.Coordinates(10, 20), coordinates.target)

        val element = parseGestureRequest(json("""{"resourceId":"menu","timeoutMs":1500}"""))
        val target = assertIs<NativeGestureTarget.Element>(element.target)
        assertEquals("menu", target.selectors.resourceId)
        assertEquals(1_500, target.timeoutMs)
    }

    @Test
    fun `gesture request rejects incomplete mixed and invalid targets`() {
        listOf(
            "{}",
            """{"x":10}""",
            """{"y":20}""",
            """{"x":-1,"y":20}""",
            """{"x":10.0,"y":20}""",
            """{"x":10,"y":20,"text":"Menu"}""",
            """{"x":10,"y":20,"timeoutMs":1000}""",
            """{"text":" "}""",
            """{"text":"Menu","timeoutMs":0}""",
            """{"text":"Menu","timeoutMs":30001}""",
            """{"x":10,"y":20,"bundleId":"app.id"}""",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { parseGestureRequest(json(value)) }
        }
    }

    @Test
    fun `targeted input preserves focused shape and parses prefixed selectors`() {
        val focused = parseTargetedInputRequest(json("""{"text":"Ada"}"""))
        assertEquals("Ada", focused.text)
        assertNull(focused.selectors)
        assertNull(focused.timeoutMs)

        val targeted = parseTargetedInputRequest(
            json("""{"text":"Ada","targetResourceId":"name","timeoutMs":1500}""")
        )
        assertEquals("name", targeted.selectors?.resourceId)
        assertEquals(1_500, targeted.timeoutMs)
    }

    @Test
    fun `targeted input rejects invalid target combinations`() {
        listOf(
            "{}",
            """{"text":1}""",
            """{"text":"Ada","timeoutMs":1000}""",
            """{"text":"Ada","targetText":" "}""",
            """{"text":"Ada","targetText":"Name","timeoutMs":false}""",
            """{"text":"Ada","bundleId":"app.id"}""",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { parseTargetedInputRequest(json(value)) }
        }
    }

    @Test
    fun `clear text rejects parameters`() {
        assertFailsWith<IllegalArgumentException> {
            requireNoParams(json("""{"text":"Name"}"""), "ui.clearText")
        }
    }

    private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
}
