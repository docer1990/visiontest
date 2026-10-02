package com.example.visiontest.tools

import com.example.visiontest.ios.IOSElementSelectors
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal class IOSInteractionToolRegistration(
    private val registrar: IOSAutomationToolRegistrar,
) {
    fun register(scope: ToolScope) {
        registerDismissKeyboard(scope)
        registerHandleAlert(scope)
        registerGesture(scope, "ios_long_press", "Long-presses", registrar::longPress)
        registerGesture(scope, "ios_double_tap", "Double-taps", registrar::doubleTap)
        registerInputText(scope)
    }

    private fun registerDismissKeyboard(scope: ToolScope) {
        scope.tool(
            "ios_dismiss_keyboard",
            "Dismisses a visible iOS software keyboard and verifies that it disappears.",
            Tool.Input(properties = buildJsonObject { stringProperty("bundleId") }),
        ) { registrar.dismissKeyboard(it.optionalString("bundleId")) }
    }

    private fun registerHandleAlert(scope: ToolScope) {
        scope.tool(
            name = "ios_handle_alert",
            description = "Accepts or dismisses an app or system alert, optionally by exact button label.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("action") {
                        put("type", "string")
                        put("enum", buildJsonArray { add("accept"); add("dismiss") })
                    }
                    stringProperty("buttonLabel")
                    stringProperty("bundleId")
                },
                required = listOf("action"),
            ),
        ) { request ->
            registrar.handleAlert(
                request.requireString("action"),
                request.optionalString("buttonLabel"),
                request.optionalString("bundleId"),
            )
        }
    }

    private fun registerGesture(
        scope: ToolScope,
        name: String,
        verb: String,
        gesture: suspend (Int?, Int?, IOSElementSelectors, Int?) -> String,
    ) {
        scope.tool(
            name = name,
            description = "$verb exactly one iOS target: coordinates or selectors. " +
                "Selector targets wait until actionable.",
            inputSchema = gestureSchema(),
            timeoutMs = INTERACTION_TOOL_TIMEOUT_MS,
        ) { request ->
            gesture(
                request.optionalInt("x"), request.optionalInt("y"),
                request.iosSelectors(), request.optionalInt("timeoutMs"),
            )
        }
    }

    private fun registerInputText(scope: ToolScope) {
        scope.tool(
            name = "ios_input_text",
            description = """
                Types text into the focused element or an optional selector target.
                Selector targets wait until editable and focused.

                WORKFLOW: Prefer ios_tap_on_element with a stable selector to focus a text field,
                then call this tool to type text into it. Use ios_tap_by_coordinates only when
                coordinates are the intended target.
            """.trimIndent(),
            inputSchema = inputSchema(),
            timeoutMs = INTERACTION_TOOL_TIMEOUT_MS,
        ) { request ->
            val selectors = request.iosTargetSelectors()
            registrar.inputText(
                request.requireString("text"), request.optionalString("bundleId"),
                selectors.takeIf { it.hasAnySelector() }, request.optionalInt("timeoutMs"),
            )
        }
    }
}

private fun CallToolRequest.iosSelectors() = IOSElementSelectors(
    text = optionalString("text"), textContains = optionalString("textContains"),
    identifier = optionalString("resourceId"), elementType = optionalString("className"),
    label = optionalString("contentDescription"), bundleId = optionalString("bundleId"),
)

private fun CallToolRequest.iosTargetSelectors() = IOSElementSelectors(
    text = optionalString("targetText"), textContains = optionalString("targetTextContains"),
    identifier = optionalString("targetResourceId"), elementType = optionalString("targetClassName"),
    label = optionalString("targetContentDescription"),
)

private fun gestureSchema() = Tool.Input(properties = buildJsonObject {
    integerProperty("x", 0)
    integerProperty("y", 0)
    selectorProperties("")
    stringProperty("bundleId")
    timeoutProperty()
})

private fun inputSchema() = Tool.Input(
    properties = buildJsonObject {
        stringProperty("text")
        selectorProperties("target")
        stringProperty("bundleId")
        timeoutProperty()
    },
    required = listOf("text"),
)

private fun kotlinx.serialization.json.JsonObjectBuilder.selectorProperties(prefix: String) {
    listOf("Text", "TextContains", "ResourceId", "ClassName", "ContentDescription").forEach { suffix ->
        stringProperty(if (prefix.isEmpty()) suffix.replaceFirstChar(Char::lowercaseChar) else prefix + suffix)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String) {
    putJsonObject(name) { put("type", "string") }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.integerProperty(name: String, minimum: Int) {
    putJsonObject(name) { put("type", "integer"); put("minimum", minimum) }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.timeoutProperty() {
    putJsonObject("timeoutMs") {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", INTERACTION_MAX_TIMEOUT_MS)
        put("default", INTERACTION_DEFAULT_TIMEOUT_MS)
    }
}
