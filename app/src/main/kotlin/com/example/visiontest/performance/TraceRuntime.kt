package com.example.visiontest.performance

import com.example.visiontest.config.VersionInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

/** Pure mode-specific activation policy. CLI deliberately does not consult the MCP environment. */
internal fun resolveTracePath(mode: String, cliPath: String?, environmentPath: String?): Path? {
    val value = when (mode) {
        "cli" -> cliPath?.also { require(it.isNotBlank()) { "--trace-performance must not be blank" } }
        "mcp" -> environmentPath?.takeIf { it.isNotBlank() }
        else -> throw IllegalArgumentException("Unknown invocation mode")
    }
    return value?.let { Path.of(it).toAbsolutePath().normalize() }
}

/** One process session; the CLI explicitly attaches its coroutine context while dispatching. */
internal class TraceRuntime(
    val mode: String,
    val entryNs: Long,
    private val diagnostic: (String) -> Unit = System.err::println,
) {
    var recorder = TraceRecorder.Disabled
        private set
    private var sink: JsonlTraceSink? = null
    private var configured = false
    private var closed: TraceCloseResult? = null
    private var dispatchContext: CoroutineContext? = null
    private var prepared = false
    var platform: String? = null

    fun configure(path: Path?) {
        if (configured) return
        configured = true
        val opened = path?.let { JsonlTraceSink.open(it, diagnostic) } ?: return
        sink = opened
        recorder = TraceRecorder(emit = { opened.offer(it.toJson().toString()); Unit }, originNs = entryNs)
        opened.offer(buildJsonObject {
            put("type", "session")
            put("schemaVersion", 1)
            put("sessionId", recorder.sessionId)
            put("visionTestVersion", VersionInfo.version)
            put("mode", mode)
            put("originNs", entryNs)
        }.toString())
    }

    @Suppress("TooGenericExceptionCaught") // Trace control flow without replacing the original throwable.
    suspend fun <T> cliInvocation(operation: String, block: suspend () -> T): T =
        recorder.invocation(operation, platform) {
            dispatchContext = kotlin.coroutines.coroutineContext
            var failure: Throwable? = null
            try {
                block()
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                preparationComplete(failure)
                dispatchContext = null
            }
        }

    fun preparationComplete(failure: Throwable? = null) {
        if (prepared) return
        val context = dispatchContext ?: return
        prepared = true
        val end = System.nanoTime()
        recorder.interval(context, TraceStage.CLI_PREPARE, entryNs, end, failure)
    }

    fun <T> initializeComponents(block: () -> T): T {
        preparationComplete()
        val context = dispatchContext ?: return block()
        return runBlocking(context) { recorder.span(TraceStage.COMPONENT_INIT) { block() } }
    }

    @Synchronized
    fun finish(): TraceCloseResult {
        closed?.let { return it }
        val result = sink?.finish(MAX_DRAIN_MS) { drained ->
            buildJsonObject {
                put("type", "session.end")
                put("sessionId", recorder.sessionId)
                put("written", drained.written)
                put("dropped", drained.dropped)
                put("invocationsStarted", recorder.invocationsStarted)
                put("invocationsCompleted", recorder.invocationsCompleted)
                put("complete", drained.complete && recorder.invocationsStarted == recorder.invocationsCompleted)
            }.toString()
        } ?: TraceCloseResult(true, 0, 0)
        val matched = recorder.invocationsStarted == recorder.invocationsCompleted
        if (result.complete && !matched) {
            runCatching { diagnostic("VisionTest performance trace is unavailable or incomplete.") }
        }
        return result.copy(complete = result.complete && matched).also { closed = it }
    }

    companion object {
        private const val MAX_DRAIN_MS = 1_000L
    }
}
