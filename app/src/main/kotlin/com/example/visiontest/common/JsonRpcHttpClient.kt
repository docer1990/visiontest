package com.example.visiontest.common

import com.example.visiontest.CommandExecutionException
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

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
