package dev.visiontest.cli

import java.nio.file.Path

/** Caller-owned inputs and diagnostics for one CLI invocation. */
internal data class CliExecutionContext(
    val workingDirectory: Path = Path.of("").toAbsolutePath().normalize(),
    val environment: Map<String, String> = System.getenv().toMap(),
    val readStdin: () -> String = { System.`in`.bufferedReader().readText() },
    val diagnostic: (String) -> Unit = System.err::println,
) {
    init {
        require(workingDirectory.isAbsolute) { "CLI working directory must be absolute" }
    }

    companion object {
        fun local(): CliExecutionContext = CliExecutionContext()
    }
}
