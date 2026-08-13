package com.example.visiontest.common

import com.example.visiontest.CommandExecutionException
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

/**
 * Base HTTP client for the JSON-RPC 2.0 automation servers.
 *
 * Both the Android and iOS automation servers expose the same surface —
 * `GET /health` and `POST /jsonrpc` — so the transport lives here and the
 * platform clients only add their domain methods.
 */
abstract class JsonRpcHttpClient(
    private val host: String,
    private val port: Int,
) {
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
    suspend fun sendRequest(method: String, params: Map<String, Any>? = null, id: Int = 1): String {
        return withContext(Dispatchers.IO) {
            val requestBody = gson.toJson(
                mapOf(
                    "jsonrpc" to "2.0",
                    "method" to method,
                    "params" to (params ?: emptyMap<String, Any>()),
                    "id" to id
                )
            )

            val url = URL("http://$host:$port/jsonrpc")
            val connection = url.openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = REQUEST_TIMEOUT_MS
                connection.readTimeout = REQUEST_TIMEOUT_MS
                connection.doOutput = true

                connection.outputStream.use { os ->
                    os.write(requestBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errorStream = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "Unknown error"
                    throw CommandExecutionException("HTTP error: $responseCode - $errorStream", responseCode)
                }

                // Close the stream (not just disconnect) so the underlying connection
                // can be returned to the keep-alive pool for reuse.
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
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
        val startNanos = System.nanoTime()
        while (true) {
            val response = find()
            val found = parseElementFound(response)
            if (found != expectGone) {
                return if (expectGone) "Element is no longer present ($selectorDescription)." else response
            }
            val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
            if (elapsedMs + pollIntervalMs > timeoutMs) {
                val condition = if (expectGone) "still present" else "not found"
                throw TimeoutException(
                    "Element $condition after ${elapsedMs}ms (waited up to ${timeoutMs}ms; $selectorDescription)"
                )
            }
            delay(pollIntervalMs)
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
        return withContext(Dispatchers.IO) {
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
    }
}
