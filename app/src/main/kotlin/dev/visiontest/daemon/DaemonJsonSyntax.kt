package dev.visiontest.daemon

import java.io.IOException

/** kotlinx JSON's tree decoder accepts unquoted values and nonstandard numeric tokens. */
internal object DaemonJsonSyntax {
    const val MAX_NESTING = 128

    fun validate(text: String) = JsonSyntaxParser(text).validate()
}

/** Checks RFC 8259 syntax before the existing tree decoder, with finite recursive nesting. */
private class JsonSyntaxParser(private val text: String) {
    private var position = 0

    fun validate() {
        value(0)
        whitespace()
        if (position != text.length) invalidJson()
    }

    private fun value(depth: Int) {
        whitespace()
        when (text.getOrNull(position)) {
            '{' -> container(depth + 1, objectMembers = true)
            '[' -> container(depth + 1, objectMembers = false)
            '"' -> string()
            '-', in '0'..'9' -> {
                val match = JSON_NUMBER.matchAt(text, position) ?: invalidJson()
                position = match.range.last + 1
            }
            else -> {
                val literal = JSON_LITERALS.firstOrNull { text.startsWith(it, position) } ?: invalidJson()
                position += literal.length
            }
        }
    }

    private fun container(depth: Int, objectMembers: Boolean) {
        if (depth > DaemonJsonSyntax.MAX_NESTING) invalidJson()
        val closing = if (objectMembers) '}' else ']'
        position++
        whitespace()
        if (consume(closing)) return
        do {
            whitespace()
            if (objectMembers) {
                string()
                whitespace()
                expect(':')
            }
            value(depth)
            whitespace()
        } while (consume(','))
        expect(closing)
    }

    private fun string() {
        expect('"')
        while (position < text.length) {
            when (val character = text[position++]) {
                '"' -> return
                '\\' -> escape()
                else -> if (character < ' ') invalidJson()
            }
        }
        invalidJson()
    }

    private fun escape() {
        when (text.getOrNull(position++)) {
            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
            'u' -> {
                val end = position + UNICODE_ESCAPE_DIGITS
                if (end > text.length) invalidJson()
                if (text.subSequence(position, end).any { it !in HEX_DIGITS }) invalidJson()
                position = end
            }
            else -> invalidJson()
        }
    }

    private fun whitespace() {
        while (position < text.length && text[position] in JSON_WHITESPACE) position++
    }

    private fun consume(character: Char): Boolean = (text.getOrNull(position) == character).also {
        if (it) position++
    }

    private fun expect(character: Char) {
        if (!consume(character)) invalidJson()
    }

    companion object {
        private const val UNICODE_ESCAPE_DIGITS = 4
        private const val HEX_DIGITS = "0123456789abcdefABCDEF"
        private const val JSON_WHITESPACE = " \t\r\n"
        private val JSON_LITERALS = listOf("true", "false", "null")
        private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}

private fun invalidJson(): Nothing = throw IOException("Invalid daemon JSON syntax or excessive nesting")
