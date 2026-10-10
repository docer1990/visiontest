package com.example.visiontest.performance

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal enum class TraceStage(val wireName: String) {
    INVOCATION("invocation"),
    CLI_PREPARE("cli.prepare"),
    COMPONENT_INIT("component.init"),
    OPERATION("operation"),
    HEALTH("health"),
    REQUEST_PREPARE("request.prepare"),
    HTTP_EXCHANGE("http.exchange"),
    RESPONSE_PROCESS("response.process"),
    POLL("poll"),
    POLL_WAIT("poll.wait"),
    SCREENSHOT_PARSE("screenshot.parse"),
    SCREENSHOT_DECODE("screenshot.decode"),
    SCREENSHOT_WRITE("screenshot.write"),
    DEVICE_DISCOVERY("device.discovery"),
    ADB("adb"),
    SIMCTL("simctl"),
    PROCESS_LAUNCH("process.launch"),
}

internal enum class TraceOutcome(val wireName: String) {
    RETURNED("returned"), THROWN("thrown"), TIMEOUT("timeout"), CANCELLED("cancelled"),
}

internal enum class OperationOutcome(val wireName: String) {
    SUCCESS("success"), FAILURE("failure"), UNKNOWN("unknown"),
}

internal enum class TraceMetric(val wireName: String) {
    REQUEST_BYTES("requestBytes"), RESPONSE_BYTES("responseBytes"), DECODED_BYTES("decodedBytes"),
    POLL_COUNT("pollCount"), WAIT_NS("waitNs"),
}

internal enum class TraceErrorCategory(val wireName: String) {
    VALIDATION("validation"), UNREACHABLE("unreachable"), DEVICE_MISSING("device_missing"),
    UNSUPPORTED_PLATFORM("unsupported_platform"), PROTOCOL("protocol"), TRANSPORT("transport"),
    IO("io"), OTHER("other"),
}

internal data class TraceEvent(
    val sessionId: String,
    val invocationId: String,
    val invocationSequence: Long,
    val spanId: String,
    val parentSpanId: String?,
    val platform: String?,
    val operation: String,
    val stage: TraceStage,
    val startOffsetNs: Long,
    val durationNs: Long,
    val outcome: TraceOutcome,
    val operationOutcome: OperationOutcome = OperationOutcome.UNKNOWN,
    val metrics: Map<TraceMetric, Long> = emptyMap(),
    val errorCategory: TraceErrorCategory? = null,
) {
    val process: String get() = "host"

    fun toJson(): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("invocationId", invocationId)
        put("invocationSequence", invocationSequence)
        put("spanId", spanId)
        put("parentSpanId", parentSpanId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("process", process)
        put("platform", TraceNames.platform(platform)?.let { JsonPrimitive(it) } ?: JsonNull)
        put("operation", TraceNames.operation(operation))
        put("stage", stage.wireName)
        put("startOffsetNs", startOffsetNs)
        put("durationNs", durationNs)
        put("outcome", outcome.wireName)
        put("operationOutcome", operationOutcome.wireName)
        errorCategory?.let { put("errorCategory", it.wireName) }
        if (metrics.isNotEmpty()) {
            putJsonObject("metrics") { metrics.forEach { (name, value) -> put(name.wireName, value) } }
        }
    }
}

internal object TraceNames {
    private val operations = setOf(
        "init", "help", "version", "other", "available_device", "list_apps", "info_app", "launch_app",
        "install_automation_server", "start_automation_server", "stop_automation_server", "automation_server_status",
        "get_ui_hierarchy", "find_element", "wait_for_element", "wait_until_gone", "tap_by_coordinates",
        "swipe", "swipe_direction", "swipe_on_element", "tap_on_element", "get_device_info",
        "get_interactive_elements", "input_text", "press_key", "clear_text", "long_press", "double_tap",
        "press_back", "press_home", "screenshot", "dismiss_keyboard", "handle_alert",
        "available_device_android", "list_apps_android", "info_app_android", "launch_app_android",
        "android_tap_by_coordinates", "android_swipe", "android_swipe_direction", "android_swipe_on_element",
        "android_get_device_info", "android_input_text", "android_press_key", "android_clear_text",
        "android_long_press", "android_double_tap", "android_press_back", "android_press_home", "android_screenshot",
        "ios_available_device", "ios_list_apps", "ios_info_app", "ios_launch_app", "ios_start_automation_server",
        "ios_automation_server_status", "ios_get_ui_hierarchy", "ios_find_element", "ios_wait_for_element",
        "ios_wait_until_gone", "ios_tap_by_coordinates", "ios_swipe", "ios_swipe_direction", "ios_swipe_on_element",
        "ios_tap_on_element", "ios_get_interactive_elements", "ios_get_device_info", "ios_input_text",
        "ios_dismiss_keyboard", "ios_handle_alert", "ios_long_press", "ios_double_tap", "ios_press_home",
        "ios_screenshot", "ios_stop_automation_server",
    )

    fun operation(value: String): String = value.takeIf { it in operations } ?: "other"

    fun toolPlatform(name: String): String? = when {
        name !in operations -> null
        name.startsWith("ios_") -> "ios"
        else -> "android"
    }

    fun platform(value: String?): String? = value?.takeIf { it == "android" || it == "ios" }
}
