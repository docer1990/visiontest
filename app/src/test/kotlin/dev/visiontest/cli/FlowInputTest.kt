package dev.visiontest.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlowInputTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `all sources split lines and ignore only whole line comments`() {
        val source = "\n # comment\r\npress_home\ninput_text '# literal'\n\t\n"
        val expected = listOf("press_home", "input_text '# literal'")
        val file = directory.resolve("flow.txt")
        Files.writeString(file, source)
        assertEquals(expected, FlowInput.read(listOf(source), null, false) { error("stdin") })
        assertEquals(expected, FlowInput.read(emptyList(), file.toString(), false) { error("stdin") })
        assertEquals(expected, FlowInput.read(emptyList(), null, true) { source })
    }

    @Test
    fun `exactly one nonempty input source is required before stdin is read`() {
        val cases = listOf(
            Triple(emptyList(), null, false),
            Triple(listOf("press_home"), "unused", false),
            Triple(listOf("press_home"), null, true),
            Triple(emptyList(), "unused", true),
            Triple(listOf("press_home"), "unused", true),
            Triple(listOf("\n # comment"), null, false),
        )
        for ((steps, file, stdin) in cases) {
            assertFailsWith<IllegalArgumentException> {
                FlowInput.read(steps, file, stdin) { error("must not read stdin") }
            }
        }
        assertFailsWith<IllegalArgumentException> { FlowInput.read(emptyList(), null, true) { "# empty" } }
    }

    @Test
    fun `tokenizer groups quotes preserves empty values and escapes next character`() {
        assertEquals(
            listOf("input_text", "hello world", "", "", "ab cd", "a\\b", "x\"y", "pre midpost"),
            FlowInput.tokenize("input_text 'hello world' \"\" '' ab\\ cd 'a\\b' \"x\\\"y\" pre' mid'post"),
        )
    }

    @Test
    fun `tokenizer leaves shell expressions and inline comments literal`() {
        assertEquals(
            listOf("input_text", "${'$'}HOME", "~", "*.txt", "${'$'}(whoami)", "#", "literal", "@file"),
            FlowInput.tokenize("input_text ${'$'}HOME ~ *.txt ${'$'}(whoami) # literal @file"),
        )
    }

    @Test
    fun `malformed quotes and dangling escapes are usage errors`() {
        for (line in listOf("input_text '", "input_text \"open", "input_text end\\", "input_text \"end\\")) {
            assertFailsWith<IllegalArgumentException>(line) { FlowInput.tokenize(line) }
        }
    }
}
