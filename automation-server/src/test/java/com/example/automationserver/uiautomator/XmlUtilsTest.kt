package com.example.automationserver.uiautomator

import kotlin.test.Test
import kotlin.test.assertEquals

class XmlUtilsTest {

    @Test
    fun `normal ASCII passes through unchanged`() {
        assertEquals("Hello World 123", stripInvalidXMLChars("Hello World 123"))
    }

    @Test
    fun `empty string returns empty string`() {
        assertEquals("", stripInvalidXMLChars(""))
    }

    @Test
    fun `tab 0x9 is preserved`() {
        assertEquals("a\tb", stripInvalidXMLChars("a\tb"))
    }

    @Test
    fun `newline 0xA is preserved`() {
        assertEquals("a\nb", stripInvalidXMLChars("a\nb"))
    }

    @Test
    fun `carriage return 0xD is preserved`() {
        assertEquals("a\rb", stripInvalidXMLChars("a\rb"))
    }

    @Test
    fun `control chars 0x1 through 0x8 replaced with dot`() {
        for (code in 0x1..0x8) {
            val input = "a${code.toChar()}b"
            assertEquals("a.b", stripInvalidXMLChars(input), "char 0x${code.toString(16)} should be replaced")
        }
    }

    @Test
    fun `chars 0xB and 0xC replaced with dot`() {
        assertEquals("a.b", stripInvalidXMLChars("a${0xB.toChar()}b"))
        assertEquals("a.b", stripInvalidXMLChars("a${0xC.toChar()}b"))
    }

    @Test
    fun `chars 0xE through 0x1F replaced with dot`() {
        for (code in 0xE..0x1F) {
            val input = "a${code.toChar()}b"
            assertEquals("a.b", stripInvalidXMLChars(input), "char 0x${code.toString(16)} should be replaced")
        }
    }

    @Test
    fun `chars 0x7F through 0x84 replaced with dot`() {
        for (code in 0x7F..0x84) {
            val input = "a${code.toChar()}b"
            assertEquals("a.b", stripInvalidXMLChars(input), "char 0x${code.toString(16)} should be replaced")
        }
    }

    @Test
    fun `char 0x85 is preserved`() {
        val input = "a${0x85.toChar()}b"
        assertEquals(input, stripInvalidXMLChars(input))
    }

    @Test
    fun `chars 0x86 through 0x9F replaced with dot`() {
        for (code in 0x86..0x9F) {
            val input = "a${code.toChar()}b"
            assertEquals("a.b", stripInvalidXMLChars(input), "char 0x${code.toString(16)} should be replaced")
        }
    }

    @Test
    fun `chars 0xFDD0 through 0xFDEF replaced with dot`() {
        for (code in 0xFDD0..0xFDEF) {
            val input = "a${code.toChar()}b"
            assertEquals("a.b", stripInvalidXMLChars(input), "char 0x${code.toString(16)} should be replaced")
        }
    }

    @Test
    fun `mixed valid and invalid chars - only invalid become dot`() {
        val input = "Hi${0x01.toChar()}World${0x0B.toChar()}!"
        assertEquals("Hi.World.!", stripInvalidXMLChars(input))
    }

    @Test
    fun `printable unicode is preserved`() {
        assertEquals("こんにちは", stripInvalidXMLChars("こんにちは"))
    }

    @Test
    fun `space character is preserved`() {
        assertEquals(" ", stripInvalidXMLChars(" "))
    }

    @Test
    fun `null char 0x0 is replaced with dot`() {
        // U+0000 is not permitted in XML 1.1
        val input = "a${0x0.toChar()}b"
        assertEquals("a.b", stripInvalidXMLChars(input))
    }

    @Test
    fun `high Unicode invalid range char 0x1FFFE replaced with dot`() {
        val highInvalidChar = String(Character.toChars(0x1FFFE))
        val input = "a${highInvalidChar}b"
        assertEquals("a.b", stripInvalidXMLChars(input))
    }

    @Test
    fun `surrogate range 0xD800 through 0xDFFF replaced with dot`() {
        // Unpaired surrogates are invalid in XML.
        // Test boundaries of the surrogate range.
        for (code in intArrayOf(0xD800, 0xDBFF, 0xDC00, 0xDFFF)) {
            val input = "a${code.toChar()}b"
            assertEquals("a.b", stripInvalidXMLChars(input), "surrogate 0x${code.toString(16)} should be replaced")
        }
    }

    @Test
    fun `BMP noncharacters 0xFFFE and 0xFFFF replaced with dot`() {
        for (code in 0xFFFE..0xFFFF) {
            val input = "a${code.toChar()}b"
            assertEquals("a.b", stripInvalidXMLChars(input), "char 0x${code.toString(16)} should be replaced")
        }
    }
}
