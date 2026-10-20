package com.example.visiontest.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal object FlowInput {
    fun read(steps: List<String>, file: String?, stdin: Boolean, readStdin: () -> String): List<String> {
        require(listOf(steps.isNotEmpty(), file != null, stdin).count { it } == 1) {
            "Specify exactly one flow input source: --step, --file, or --stdin"
        }
        val sources = when {
            file != null -> listOf(readFile(file))
            stdin -> listOf(readStdin())
            else -> steps
        }
        return sources.flatMap(String::lines).filter { it.isNotBlank() && !it.trimStart().startsWith('#') }
            .also { require(it.isNotEmpty()) { "Flow must contain at least one step" } }
    }

    private fun readFile(file: String): String {
        require(file.isNotBlank()) { "Flow file path must not be blank" }
        return try {
            Files.readString(Path.of(file))
        } catch (error: IOException) {
            throw IllegalArgumentException("Cannot read flow file: $file", error)
        }
    }

    fun tokenize(line: String): List<String> {
        require('\n' !in line && '\r' !in line) { "Each flow step must occupy one line" }
        return FlowTokenizer().parse(line)
    }
}

private class FlowTokenizer {
    private val tokens = mutableListOf<String>()
    private val token = StringBuilder()
    private var quote: Char? = null
    private var escaped = false
    private var started = false

    fun parse(line: String): List<String> {
        line.forEach(::consume)
        require(!escaped) { "Dangling escape in flow step" }
        require(quote == null) { "Unclosed quote in flow step" }
        finishToken()
        return tokens
    }

    private fun consume(character: Char) {
        when {
            escaped -> {
                token.append(character)
                escaped = false
            }
            character == '\\' && quote != '\'' -> {
                started = true
                escaped = true
            }
            quote != null -> if (character == quote) quote = null else token.append(character)
            character == '\'' || character == '"' -> {
                quote = character
                started = true
            }
            character.isWhitespace() -> finishToken()
            else -> {
                started = true
                token.append(character)
            }
        }
    }

    private fun finishToken() {
        if (started) tokens += token.toString()
        token.clear()
        started = false
    }
}
