package com.example.automationserver.uiautomator

/**
 * Strips invalid XML characters from a CharSequence.
 *
 * Based on XML 1.1 specification character ranges.
 * See: http://www.w3.org/TR/xml11/#charsets
 */
@Suppress("ComplexCondition")
internal fun stripInvalidXMLChars(cs: CharSequence): String {
    val ret = StringBuilder()
    for (i in cs.indices) {
        val ch = cs[i]
        val code = ch.code
        // Invalid XML character ranges per XML 1.1 spec
        if (code in 0x1..0x8 ||
            code in 0xB..0xC ||
            code in 0xE..0x1F ||
            code in 0x7F..0x84 ||
            code in 0x86..0x9F ||
            code in 0xFDD0..0xFDDF ||
            code in 0x1FFFE..0x1FFFF ||
            code in 0x2FFFE..0x2FFFF ||
            code in 0x3FFFE..0x3FFFF ||
            code in 0x4FFFE..0x4FFFF ||
            code in 0x5FFFE..0x5FFFF ||
            code in 0x6FFFE..0x6FFFF ||
            code in 0x7FFFE..0x7FFFF ||
            code in 0x8FFFE..0x8FFFF ||
            code in 0x9FFFE..0x9FFFF ||
            code in 0xAFFFE..0xAFFFF ||
            code in 0xBFFFE..0xBFFFF ||
            code in 0xCFFFE..0xCFFFF ||
            code in 0xDFFFE..0xDFFFF ||
            code in 0xEFFFE..0xEFFFF ||
            code in 0xFFFFE..0xFFFFF ||
            code in 0x10FFFE..0x10FFFF
        ) {
            ret.append(".")
        } else {
            ret.append(ch)
        }
    }
    return ret.toString()
}
