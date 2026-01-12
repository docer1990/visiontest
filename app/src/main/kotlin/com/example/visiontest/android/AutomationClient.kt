package com.example.visiontest.android

import com.example.visiontest.CommandExecutionException
import com.example.visiontest.config.AutomationConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for communicating with the Automation Server running on an Android device.
 *
 * @param host The host to connect to (default: localhost via ADB port forwarding)
 * @param port The port number (default: [AutomationConfig.DEFAULT_PORT])
 */
class AutomationClient(
    private val host: String = AutomationConfig.DEFAULT_HOST,
    private val port: Int = AutomationConfig.DEFAULT_PORT
) {
    companion object {
        private const val TIMEOUT_MS = 30000

        // Gson is thread-safe and stateless, so we can share a single instance
        private val gson = Gson()
    }

    /**
     * Sends a JSON-RPC request to the automation server.
     */
    suspend fun sendRequest(method: String, params: Map<String, Any>? = null, id: Int = 1): String {
        return withContext(Dispatchers.IO) {
            val paramsJson = if (params != null) {
                params.entries.joinToString(",", "{", "}") { (k, v) ->
                    "\"$k\":${valueToJson(v)}"
                }
            } else {
                "{}"
            }

            val requestBody = """{"jsonrpc":"2.0","method":"$method","params":$paramsJson,"id":$id}"""

            val url = URL("http://$host:$port/jsonrpc")
            val connection = url.openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.doOutput = true

                connection.outputStream.use { os ->
                    os.write(requestBody.toByteArray())
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                    throw CommandExecutionException("HTTP error: $responseCode - $errorStream", responseCode)
                }

                connection.inputStream.bufferedReader().readText()
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
            try {
                val url = URL("http://$host:$port/health")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                connection.disconnect()
                responseCode == HttpURLConnection.HTTP_OK
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Gets the UI hierarchy from the device.
     */
    suspend fun getUiHierarchy(): String {
        return sendRequest("ui.dumpHierarchy")
    }

    /**
     * Gets device information.
     */
    suspend fun getDeviceInfo(): String {
        return sendRequest("device.getInfo")
    }

    /**
     * Clicks at the specified coordinates.
     */
    suspend fun click(x: Int, y: Int): String {
        return sendRequest("ui.click", mapOf("x" to x, "y" to y))
    }

    /**
     * Presses the back button.
     */
    suspend fun pressBack(): String {
        return sendRequest("device.pressBack")
    }

    /**
     * Presses the home button.
     */
    suspend fun pressHome(): String {
        return sendRequest("device.pressHome")
    }

    /**
     * Finds an element by selector. Returns element info if found.
     * @param text Exact text match
     * @param textContains Partial text match
     * @param resourceId Resource ID (e.g., "com.example:id/button")
     * @param className Class name (e.g., "android.widget.Button")
     * @param contentDescription Content description for accessibility
     */
    suspend fun findElement(
        text: String? = null,
        textContains: String? = null,
        resourceId: String? = null,
        className: String? = null,
        contentDescription: String? = null
    ): String {
        val params = mutableMapOf<String, Any>()
        text?.let { params["text"] = it }
        textContains?.let { params["textContains"] = it }
        resourceId?.let { params["resourceId"] = it }
        className?.let { params["className"] = it }
        contentDescription?.let { params["contentDescription"] = it }

        return sendRequest("ui.findElement", params.ifEmpty { null })
    }

    private fun valueToJson(value: Any): String = gson.toJson(value)
}
