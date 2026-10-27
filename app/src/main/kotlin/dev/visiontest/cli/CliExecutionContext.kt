package dev.visiontest.cli

import java.nio.file.Path
import kotlinx.coroutines.asContextElement
import dev.visiontest.common.RequestLogSettings

/** Caller-owned inputs and diagnostics for one CLI invocation. */
internal data class CliExecutionContext(
    val workingDirectory: Path = Path.of("").toAbsolutePath().normalize(),
    val environment: Map<String, String> = System.getenv().toMap(),
    val readStdin: () -> String = { System.`in`.bufferedReader().readText() },
    val diagnostic: (String) -> Unit = System.err::println,
    val loggingProperties: Map<String, String> = System.getProperties().stringPropertyNames()
        .filter { it.startsWith("org.slf4j.simpleLogger.") }.associateWith { System.getProperty(it) },
    val logOutput: ((String) -> Unit)? = null,
) {
    internal val logSettings = RequestLogSettings(loggingProperties)
    internal val loggingStartedAtMillis = java.lang.management.ManagementFactory.getRuntimeMXBean().startTime
    init {
        require(workingDirectory.isAbsolute) { "CLI working directory must be absolute" }
    }

    fun resolve(path: String): Path = workingDirectory.resolve(path).normalize()

    fun environmentValue(name: String): String? = environment[name]

    fun coroutineContext() = active.asContextElement(this)

    fun <T> withCurrent(block: () -> T): T {
        val previous = active.get()
        active.set(this)
        return try {
            block()
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    companion object {
        private val active = ThreadLocal<CliExecutionContext?>()
        internal fun currentOrNull(): CliExecutionContext? = active.get()
        internal fun current(): CliExecutionContext = currentOrNull() ?: local()
        fun local(): CliExecutionContext = CliExecutionContext()
    }
}
