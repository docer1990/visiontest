package com.example.visiontest.common

import com.example.visiontest.CommandExecutionException
import com.example.visiontest.performance.OperationOutcome
import com.example.visiontest.performance.TraceMetric
import com.example.visiontest.performance.TraceRecorder
import com.example.visiontest.performance.TraceStage
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject as StructuredJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeoutException

/**
 * Formats non-null selectors as `text='Login', resourceId='btn'` for wait/find messages.
 */
internal fun describeSelectors(vararg selectors: Pair<String, String?>): String {
    val present = selectors.filter { it.second != null }
    if (present.isEmpty()) return "no selectors"
    return present.joinToString(", ") { (name, value) -> "$name='$value'" }
}

/** Validates a native element-tap timeout before adding its HTTP transport grace period. */
internal fun elementTapReadTimeoutMs(timeoutMs: Int, maxTimeoutMs: Long, graceMs: Long): Int {
    require(timeoutMs in 1..maxTimeoutMs.toInt()) {
        "timeoutMs must be between 1 and $maxTimeoutMs, got $timeoutMs"
    }
    return Math.addExact(timeoutMs, graceMs.toInt())
}

/**
 * Base HTTP client for the JSON-RPC 2.0 automation servers.
 *
 * Both the Android and iOS automation servers expose the same surface —
 * `GET /health` and `POST /jsonrpc` — so the transport lives here and the
 * platform clients only add their domain methods.
 */
abstract class JsonRpcHttpClient internal constructor(
    private val host: String,
    private val port: Int,
    private val trace: TraceRecorder,
) {
    constructor(host: String, port: Int) : this(host, port, TraceRecorder.Disabled)
    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000
        const val HEALTH_TIMEOUT_MS = 5_000
        const val NANOS_PER_MILLI = 1_000_000L

        // Gson is thread-safe and stateless, so we can share a single instance
        val gson = Gson()
    }

    /**
     * Sends a JSON-RPC request to the automation server and returns the raw response body.
     */
    suspend fun sendRequest(
        method: String,
        params: Map<String, Any>? = null,
        id: Int = 1,
    ): String = sendRequest(method, params, id, REQUEST_TIMEOUT_MS)

    /**
     * Sends a JSON-RPC request with a method-specific HTTP read timeout.
     */
    suspend fun sendRequest(
        method: String,
        params: Map<String, Any>? = null,
        id: Int = 1,
        readTimeoutMs: Int = REQUEST_TIMEOUT_MS,
    ): String {
        return withContext(Dispatchers.IO) {
            val requestBytes = trace.span(TraceStage.REQUEST_PREPARE) {
                gson.toJson(
                    mapOf(
                        "jsonrpc" to "2.0",
                        "method" to method,
                        "params" to (params ?: emptyMap<String, Any>()),
                        "id" to id
                    )
                ).toByteArray(Charsets.UTF_8)
            }
            val responseBytes = trace.span(TraceStage.HTTP_EXCHANGE) {
                exchange(requestBytes, readTimeoutMs)
            }
            trace.span(TraceStage.RESPONSE_PROCESS) {
                val response = responseBytes.toString(Charsets.UTF_8)
                if (trace !== TraceRecorder.Disabled) trace.operationOutcome(responseOutcome(response))
                response
            }
        }
    }

    private suspend fun exchange(requestBytes: ByteArray, readTimeoutMs: Int): ByteArray {
        val connection = URL("http://$host:$port/jsonrpc").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = REQUEST_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.outputStream.use { it.write(requestBytes) }
            trace.metric(TraceMetric.REQUEST_BYTES, requestBytes.size.toLong())
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBytes = connection.errorStream?.use { it.readBytes() }
                errorBytes?.let { trace.metric(TraceMetric.RESPONSE_BYTES, it.size.toLong()) }
                val error = errorBytes?.toString(Charsets.UTF_8) ?: "Unknown error"
                throw CommandExecutionException("HTTP error: $responseCode - $error", responseCode)
            }
            // Close the stream so the underlying connection can return to the keep-alive pool.
            val responseBytes = connection.inputStream.use { it.readBytes() }
            trace.metric(TraceMetric.RESPONSE_BYTES, responseBytes.size.toLong())
            return responseBytes
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Repeatedly evaluates [find] (a `ui.findElement` call) until the element's presence
     * matches the expectation or [timeoutMs] elapses.
     *
     * Server failures always propagate — a dead server must never be reported as
     * "element gone". A JSON-RPC `error` member in the response is treated as a
     * server failure for the same reason.
     *
     * @param expectGone false = wait for the element to appear, true = wait for it to disappear
     * @param selectorDescription human-readable selector summary used in messages
     * @return the raw findElement response when waiting for appearance, or a
     *         confirmation message when waiting for disappearance
     * @throws TimeoutException when the deadline elapses before the expectation is met
     */
    suspend fun pollForElement(
        expectGone: Boolean,
        timeoutMs: Long,
        pollIntervalMs: Long,
        selectorDescription: String,
        find: suspend () -> String,
    ): String {
        return trace.span(TraceStage.POLL) {
            pollUntilMatched(expectGone, timeoutMs, pollIntervalMs, selectorDescription, find)
        }
    }

    private suspend fun pollUntilMatched(
        expectGone: Boolean,
        timeoutMs: Long,
        pollIntervalMs: Long,
        selectorDescription: String,
        find: suspend () -> String,
    ): String {
        val startNanos = System.nanoTime()
        while (true) {
            trace.metric(TraceMetric.POLL_COUNT, 1L)
            val response = find()
            val found = trace.span(TraceStage.RESPONSE_PROCESS) { parseElementFound(response) }
            if (found != expectGone) {
                trace.operationOutcome(OperationOutcome.SUCCESS)
                return if (expectGone) "Element is no longer present ($selectorDescription)." else response
            }
            val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
            if (elapsedMs + pollIntervalMs > timeoutMs) {
                val condition = if (expectGone) "still present" else "not found"
                throw TimeoutException(
                    "Element $condition after ${elapsedMs}ms (waited up to ${timeoutMs}ms; $selectorDescription)"
                )
            }
            pollWait(pollIntervalMs)
        }
    }

    /** Cumulative actual time spent in explicit delay, including interrupted waits. */
    private suspend fun pollWait(pollIntervalMs: Long) {
        val start = if (trace === TraceRecorder.Disabled) null else System.nanoTime()
        try {
            trace.span(TraceStage.POLL_WAIT) { delay(pollIntervalMs) }
        } finally {
            if (start != null) trace.metric(TraceMetric.WAIT_NS, (System.nanoTime() - start).coerceAtLeast(0L))
        }
    }

    /**
     * Extracts `result.found` from a raw findElement JSON-RPC response.
     * Both automation servers return an element result with a `found` boolean.
     */
    private fun parseElementFound(response: String): Boolean {
        val body = parseJsonObject(response)
        val error = body.get("error")
        if (error != null && !error.isJsonNull) {
            throw CommandExecutionException("Automation server returned an error: $error")
        }
        val result = body.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw CommandExecutionException("findElement response has no result object: $response")
        return readFoundFlag(result, response)
    }

    /**
     * A missing or non-boolean `found` is a protocol violation, not "element absent":
     * defaulting it to false would let [pollForElement] report a malformed response
     * as the element having disappeared.
     */
    private fun readFoundFlag(result: JsonObject, response: String): Boolean {
        val found = result.get("found")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?: throw CommandExecutionException("findElement response has no boolean 'found' field: $response")
        return found.asBoolean
    }

    private fun parseJsonObject(response: String): JsonObject {
        return try {
            JsonParser.parseString(response).asJsonObject
        } catch (e: JsonParseException) {
            throw CommandExecutionException("Malformed findElement response from automation server: ${e.message}")
        } catch (ignored: IllegalStateException) {
            throw CommandExecutionException("Unexpected findElement response from automation server: $response")
        }
    }

    /**
     * Checks if the automation server is running.
     */
    suspend fun isServerRunning(): Boolean {
        return trace.span(TraceStage.HEALTH) {
            val running = withContext(Dispatchers.IO) {
                val connection = try {
                    URL("http://$host:$port/health").openConnection() as HttpURLConnection
                } catch (e: Exception) {
                    return@withContext false
                }
                try {
                    connection.connectTimeout = HEALTH_TIMEOUT_MS
                    connection.readTimeout = HEALTH_TIMEOUT_MS
                    connection.requestMethod = "GET"
                    connection.responseCode == HttpURLConnection.HTTP_OK
                } catch (e: Exception) {
                    false
                } finally {
                    connection.disconnect()
                }
            }
            trace.operationOutcome(if (running) OperationOutcome.SUCCESS else OperationOutcome.FAILURE)
            running
        }
    }
}

/** Best-effort structured classification never changes the raw returned response. */
private fun responseOutcome(response: String): OperationOutcome {
    return try {
        val parsed = parseTraceResponse(response)
        if (parsed == null || !hasValidJsonPrimitives(parsed)) return OperationOutcome.UNKNOWN
        val body = parsed as? StructuredJsonObject
        val error = body?.get("error")
        val result = body?.get("result")
        when {
            error is StructuredJsonObject && !body.containsKey("result") -> OperationOutcome.FAILURE
            result is StructuredJsonObject && (error == null || error == JsonNull) -> {
                val success = (result["success"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                when (success) {
                    true -> OperationOutcome.SUCCESS
                    false -> OperationOutcome.FAILURE
                    null -> OperationOutcome.UNKNOWN
                }
            }
            else -> OperationOutcome.UNKNOWN
        }
    } catch (ignored: SerializationException) {
        OperationOutcome.UNKNOWN
    }
}

private fun parseTraceResponse(response: String): JsonElement? {
    return if (containsInvalidJsonControls(response) || exceedsTraceJsonDepth(response)) {
        null
    } else {
        Json.parseToJsonElement(response)
    }
}

// Bound parser recursion before building a tree; deeper bodies still return unchanged.
private const val MAX_TRACE_JSON_DEPTH = 64

private val jsonBooleanLiterals = setOf("true", "false")
private val jsonNumberPattern = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

/** The tree parser retains arbitrary bare tokens; require RFC JSON literals throughout the body. */
private fun hasValidJsonPrimitives(root: JsonElement): Boolean {
    val pending = ArrayDeque<JsonElement>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        when (val element = pending.removeLast()) {
            is StructuredJsonObject -> pending.addAll(element.values)
            is JsonArray -> pending.addAll(element)
            JsonNull -> Unit
            is JsonPrimitive -> if (!element.isString && element.content !in jsonBooleanLiterals &&
                !jsonNumberPattern.matches(element.content)) return false
        }
    }
    return true
}

/** Bound recursive parsing while ignoring brackets in strings, including escaped quotes. */
private fun exceedsTraceJsonDepth(response: String): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in response) {
        if (escaped) {
            escaped = false
            continue
        }
        when (character) {
            '\\' -> escaped = quoted
            '"' -> quoted = !quoted
            '{', '[' -> if (!quoted) depth++
            '}', ']' -> if (!quoted) depth--
        }
        if (depth > MAX_TRACE_JSON_DEPTH) return true
    }
    return false
}

/** Raw string controls are invalid; escaped controls and formatting whitespace remain valid. */
private fun containsInvalidJsonControls(response: String): Boolean {
    var quoted = false
    var escaped = false
    for (character in response) {
        if (character < ' ' && (quoted || character !in "\t\r\n")) return true
        if (escaped) {
            escaped = false
            continue
        }
        when (character) {
            '\\' -> escaped = quoted
            '"' -> quoted = !quoted
        }
    }
    return false
}
