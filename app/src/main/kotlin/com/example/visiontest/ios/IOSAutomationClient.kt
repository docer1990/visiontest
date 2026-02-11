package com.example.visiontest.ios

import com.example.visiontest.CommandExecutionException
import com.example.visiontest.config.IOSAutomationConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for communicating with the iOS Automation Server running on an iOS simulator.
 *
 * Unlike Android, iOS simulators share the Mac's network stack, so no port forwarding
 * is needed — the XCUITest HTTP server is directly accessible at localhost.
 *
 * @param host The host to connect to (default: localhost)
 * @param port The port number (default: [IOSAutomationConfig.DEFAULT_PORT])
 */
class IOSAutomationClient(
    private val host: String = IOSAutomationConfig.DEFAULT_HOST,
    private val port: Int = IOSAutomationConfig.DEFAULT_PORT
) {
    companion object {
        private const val TIMEOUT_MS = 30000
        private val gson = Gson()
    }

    /**
     * Sends a JSON-RPC request to the iOS automation server.
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
     * Checks if the iOS automation server is running.
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
     * Gets the UI hierarchy from the iOS simulator.
     * @param bundleId Bundle ID of the app to query. If null, queries springboard.
     */
    suspend fun getUiHierarchy(bundleId: String? = null): String {
        val params = bundleId?.let { mapOf("bundleId" to it) }
        return sendRequest("ui.dumpHierarchy", params)
    }

    /**
     * Gets device information.
     */
    suspend fun getDeviceInfo(): String {
        return sendRequest("device.getInfo")
    }

    /**
     * Gets a filtered list of interactive elements on the current screen.
     *
     * @param includeDisabled Whether to include disabled elements (default: false)
     * @param bundleId Bundle ID of the app to query. If null, queries springboard.
     */
    suspend fun getInteractiveElements(includeDisabled: Boolean = false, bundleId: String? = null): String {
        val params = mutableMapOf<String, Any>()
        if (includeDisabled) params["includeDisabled"] = true
        bundleId?.let { params["bundleId"] = it }
        return sendRequest("ui.getInteractiveElements", params.ifEmpty { null })
    }

    /**
     * Taps at the specified coordinates.
     */
    suspend fun tapByCoordinates(x: Int, y: Int): String {
        return sendRequest("ui.tapByCoordinates", mapOf("x" to x, "y" to y))
    }

    /**
     * Performs a swipe from one coordinate to another.
     */
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, steps: Int): String {
        return sendRequest(
            "ui.swipe",
            mapOf(
                "startX" to startX,
                "startY" to startY,
                "endX" to endX,
                "endY" to endY,
                "steps" to steps
            )
        )
    }

    /**
     * Performs a swipe in the specified direction.
     *
     * @param direction The direction to swipe: "up", "down", "left", "right"
     * @param distance The distance preset: "short", "medium" (default), "long"
     * @param speed The speed preset: "slow", "normal" (default), "fast"
     */
    suspend fun swipeByDirection(
        direction: String,
        distance: String = "medium",
        speed: String = "normal"
    ): String {
        return sendRequest(
            "ui.swipeByDirection",
            mapOf(
                "direction" to direction,
                "distance" to distance,
                "speed" to speed
            )
        )
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
     * @param identifier Accessibility identifier
     * @param elementType Element type name (e.g., "Button")
     * @param label Accessibility label
     */
    suspend fun findElement(
        text: String? = null,
        textContains: String? = null,
        identifier: String? = null,
        elementType: String? = null,
        label: String? = null,
        bundleId: String? = null
    ): String {
        val params = mutableMapOf<String, Any>()
        text?.let { params["text"] = it }
        textContains?.let { params["textContains"] = it }
        identifier?.let { params["resourceId"] = it }
        elementType?.let { params["className"] = it }
        label?.let { params["contentDescription"] = it }
        bundleId?.let { params["bundleId"] = it }

        return sendRequest("ui.findElement", params.ifEmpty { null })
    }

    private fun valueToJson(value: Any): String = gson.toJson(value)
}
