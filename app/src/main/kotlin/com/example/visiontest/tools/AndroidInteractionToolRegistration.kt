package com.example.visiontest.tools

import com.example.visiontest.android.AndroidElementSelectors
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal class AndroidInteractionToolRegistration(
    private val registrar: AndroidAutomationToolRegistrar,
) {
    fun register(scope: ToolScope) {
        registerPressKey(scope)
        scope.tool("android_clear_text", "Clears the currently focused editable Android element.") { request ->
            request.rejectArguments(request.arguments.keys, "android_clear_text does not accept arguments")
            registrar.clearText()
        }
        registerGesture(scope, "android_long_press", "Long-presses", registrar::longPress)
        registerGesture(scope, "android_double_tap", "Double-taps", registrar::doubleTap)
        registerInputText(scope)
    }

    private fun registerPressKey(scope: ToolScope) {
        scope.tool(
            name = "android_press_key",
            description = "Presses one Android key by keyCode or named action: enter, tab, backspace, delete, escape.",
            inputSchema = Tool.Input(properties = buildJsonObject {
                putJsonObject("keyCode") {
                    put("type", "integer")
                    put("minimum", 0)
                    put("maximum", Int.MAX_VALUE)
                }
                putJsonObject("action") {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        listOf("enter", "tab", "backspace", "delete", "escape").forEach { add(it) }
                    })
                }
            }),
        ) { request -> registrar.pressKey(request.optionalInt("keyCode"), request.optionalString("action")) }
    }

    private fun registerGesture(
        scope: ToolScope,
        name: String,
        verb: String,
        gesture: suspend (Int?, Int?, AndroidElementSelectors, Int?) -> String,
    ) {
        scope.tool(
            name = name,
            description = "$verb exactly one Android target: coordinates or selectors. " +
                "Selector targets wait until actionable.",
            inputSchema = interactionSchema(),
            timeoutMs = INTERACTION_TOOL_TIMEOUT_MS,
        ) { request ->
            request.rejectArguments(setOf("bundleId"), "Android interactions do not support bundleId")
            gesture(
                request.optionalInt("x"),
                request.optionalInt("y"),
                request.androidSelectors(),
                request.optionalInt("timeoutMs"),
            )
        }
    }

    private fun registerInputText(scope: ToolScope) {
        scope.tool(
            name = "android_input_text",
            description = """
                Types text into the focused element or an optional selector target.
                Selector targets wait until editable and focused.

                WORKFLOW: Prefer tap_on_element with a stable selector to focus a text field,
                then call this tool to type text into it. Use android_tap_by_coordinates only when
                coordinates are the intended target.
            """.trimIndent(),
            inputSchema = targetedInputSchema(),
            timeoutMs = INTERACTION_TOOL_TIMEOUT_MS,
        ) { request ->
            request.rejectArguments(setOf("bundleId"), "Android interactions do not support bundleId")
            val selectors = request.androidTargetSelectors()
            registrar.inputText(
                text = request.requireString("text"),
                selectors = selectors.takeIf { it.hasAnySelector() },
                timeoutMs = request.optionalInt("timeoutMs"),
            )
        }
    }
}

private fun CallToolRequest.androidSelectors() = AndroidElementSelectors(
    text = optionalString("text"),
    textContains = optionalString("textContains"),
    resourceId = optionalString("resourceId"),
    className = optionalString("className"),
    contentDescription = optionalString("contentDescription"),
)

private fun CallToolRequest.androidTargetSelectors() = AndroidElementSelectors(
    text = optionalString("targetText"),
    textContains = optionalString("targetTextContains"),
    resourceId = optionalString("targetResourceId"),
    className = optionalString("targetClassName"),
    contentDescription = optionalString("targetContentDescription"),
)

private fun interactionSchema() = Tool.Input(properties = buildJsonObject {
    coordinateProperties()
    selectorProperties("")
    timeoutProperty()
})

private fun targetedInputSchema() = Tool.Input(
    properties = buildJsonObject {
        putJsonObject("text") { put("type", "string") }
        selectorProperties("target")
        timeoutProperty()
    },
    required = listOf("text"),
)

private fun kotlinx.serialization.json.JsonObjectBuilder.coordinateProperties() {
    putJsonObject("x") { put("type", "integer"); put("minimum", 0) }
    putJsonObject("y") { put("type", "integer"); put("minimum", 0) }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.selectorProperties(prefix: String) {
    val suffixes = listOf("Text", "TextContains", "ResourceId", "ClassName", "ContentDescription")
    suffixes.forEach { suffix ->
        val name = if (prefix.isEmpty()) suffix.replaceFirstChar(Char::lowercaseChar) else prefix + suffix
        putJsonObject(name) { put("type", "string") }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.timeoutProperty() {
    putJsonObject("timeoutMs") {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", INTERACTION_MAX_TIMEOUT_MS)
        put("default", INTERACTION_DEFAULT_TIMEOUT_MS)
    }
}
