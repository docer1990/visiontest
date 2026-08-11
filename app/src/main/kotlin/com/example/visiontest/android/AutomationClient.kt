package com.example.visiontest.android

import com.example.visiontest.common.JsonRpcHttpClient
import com.example.visiontest.config.AutomationConfig

/**
 * Client for communicating with the Automation Server running on an Android device.
 *
 * Transport (JSON-RPC over HTTP, health check) lives in [JsonRpcHttpClient];
 * this class only exposes the Android automation methods.
 *
 * @param host The host to connect to (default: localhost via ADB port forwarding)
 * @param port The port number (default: [AutomationConfig.DEFAULT_PORT])
 */
class AutomationClient(
    host: String = AutomationConfig.DEFAULT_HOST,
    port: Int = AutomationConfig.DEFAULT_PORT
) : JsonRpcHttpClient(host, port) {

    /**
     * Gets the UI hierarchy from the device.
     */
    suspend fun getUiHierarchy(): String {
        return sendRequest("ui.dumpHierarchy")
    }

    /**
     * Captures the current device display as a PNG and returns the raw JSON-RPC response.
     * The response `result` contains `success: Boolean`, `pngBase64: String?`, and `error: String?`.
     */
    suspend fun screenshot(): String {
        return sendRequest("ui.screenshot")
    }

    /**
     * Gets device information.
     */
    suspend fun getDeviceInfo(): String {
        return sendRequest("device.getInfo")
    }

    /**
     * Gets a filtered list of interactive elements on the current screen.
     * Uses heuristics to identify elements that are likely meaningful to interact with.
     *
     * @param includeDisabled Whether to include disabled elements (default: false)
     */
    suspend fun getInteractiveElements(includeDisabled: Boolean = false): String {
        return sendRequest(
            "ui.getInteractiveElements",
            if (includeDisabled) mapOf("includeDisabled" to true) else null
        )
    }

    /**
     * Taps at the specified screen coordinates.
     */
    suspend fun tapByCoordinates(x: Int, y: Int): String {
        return sendRequest("ui.tapByCoordinates", mapOf("x" to x, "y" to y))
    }

    /**
     * Performs a swipe from one coordinate to another on the default display using the number
     * of steps to determine smoothness and speed
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
     * Automatically calculates coordinates based on screen dimensions.
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
     * Performs a swipe on a specific element in the specified direction.
     * Finds the element first, then swipes within its bounds.
     *
     * @param direction The direction to swipe: "up", "down", "left", "right"
     * @param text Exact text match for finding the element
     * @param textContains Partial text match
     * @param resourceId Resource ID (e.g., "com.example:id/carousel")
     * @param className Class name
     * @param contentDescription Accessibility content description
     * @param speed The speed preset: "slow", "normal" (default), "fast"
     */
    suspend fun swipeOnElement(
        direction: String,
        text: String? = null,
        textContains: String? = null,
        resourceId: String? = null,
        className: String? = null,
        contentDescription: String? = null,
        speed: String = "normal"
    ): String {
        val params = mutableMapOf<String, Any>(
            "direction" to direction,
            "speed" to speed
        )
        text?.let { params["text"] = it }
        textContains?.let { params["textContains"] = it }
        resourceId?.let { params["resourceId"] = it }
        className?.let { params["className"] = it }
        contentDescription?.let { params["contentDescription"] = it }

        return sendRequest("ui.swipeOnElement", params)
    }

    /**
     * Types text into the currently focused element.
     */
    suspend fun inputText(text: String): String {
        return sendRequest("ui.inputText", mapOf("text" to text))
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
}
