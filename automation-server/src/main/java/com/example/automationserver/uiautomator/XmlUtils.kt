package com.example.automationserver.uiautomator

/**
 * Strips invalid XML characters from a CharSequence.
 *
 * Based on XML 1.1 specification character ranges.
 * See: http://www.w3.org/TR/xml11/#charsets
 */
@Suppress("ComplexCondition")
internal fun stripInvalidXMLChars(cs: CharSequence): String {
    val ret = StringBuilder(cs.length)
    var i = 0
    while (i < cs.length) {
        val codePoint = Character.codePointAt(cs, i)
        val charCount = Character.charCount(codePoint)
        // Invalid XML character ranges per XML 1.1 spec
        if (codePoint == 0x0 ||
            codePoint in 0x1..0x8 ||
            codePoint in 0xB..0xC ||
            codePoint in 0xE..0x1F ||
            codePoint in 0x7F..0x84 ||
            codePoint in 0x86..0x9F ||
            codePoint in 0xD800..0xDFFF ||
            codePoint in 0xFDD0..0xFDEF ||
            codePoint in 0xFFFE..0xFFFF ||
            codePoint in 0x1FFFE..0x1FFFF ||
            codePoint in 0x2FFFE..0x2FFFF ||
            codePoint in 0x3FFFE..0x3FFFF ||
            codePoint in 0x4FFFE..0x4FFFF ||
            codePoint in 0x5FFFE..0x5FFFF ||
            codePoint in 0x6FFFE..0x6FFFF ||
            codePoint in 0x7FFFE..0x7FFFF ||
            codePoint in 0x8FFFE..0x8FFFF ||
            codePoint in 0x9FFFE..0x9FFFF ||
            codePoint in 0xAFFFE..0xAFFFF ||
            codePoint in 0xBFFFE..0xBFFFF ||
            codePoint in 0xCFFFE..0xCFFFF ||
            codePoint in 0xDFFFE..0xDFFFF ||
            codePoint in 0xEFFFE..0xEFFFF ||
            codePoint in 0xFFFFE..0xFFFFF ||
            codePoint in 0x10FFFE..0x10FFFF
        ) {
            ret.append(".")
        } else {
            ret.append(cs, i, i + charCount)
        }
        i += charCount
    }
    return ret.toString()
}
