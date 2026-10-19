package com.example.visiontest.cli

import kotlinx.serialization.json.*

internal data class FlowStepReport(
    val id: String,
    val command: String,
    val status: String,
    val certainty: String = "confirmed",
    val result: JsonObject? = null,
    val error: FlowError? = null,
)

internal data class FlowError(val type: String, val message: String)

internal data class FlowReport(
    val platform: Platform,
    val success: Boolean,
    val exitCode: Int,
    val steps: List<FlowStepReport>,
    val error: FlowError? = null,
) {
    val failedStep: String? get() = steps.firstOrNull { it.status in setOf("failed", "uncertain") }?.id
    val skippedSteps: List<String> get() = steps.filter { it.status == "skipped" }.map { it.id }

    fun toJson(): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put("platform", platform.value)
        put("success", success)
        put("exitCode", exitCode)
        put("steps", buildJsonArray { steps.forEach { add(it.toJson()) } })
        put("failedStep", failedStep?.let(::JsonPrimitive) ?: JsonNull)
        put("skippedSteps", buildJsonArray { skippedSteps.forEach { add(it) } })
        put("observations", JsonArray(emptyList()))
        put("evidence", JsonArray(emptyList()))
        put("error", error?.toJson() ?: JsonNull)
    }

    fun text(): String = buildString {
        append("Flow ${if (success) "succeeded" else "failed"}: ")
        append("${steps.count { it.status == "succeeded" }} completed, ${skippedSteps.size} skipped")
        failedStep?.let { append("; failed step $it") }
        error?.let { append(". ${it.message}") }
        steps.mapNotNull { it.result?.get("path") as? JsonPrimitive }.forEach { append("\n${it.content}") }
    }
}

private fun FlowStepReport.toJson() = buildJsonObject {
    put("id", id)
    put("command", command)
    put("status", status)
    put("certainty", certainty)
    put("result", result ?: JsonNull)
    put("error", error?.toJson() ?: JsonNull)
}

private fun FlowError.toJson() = buildJsonObject {
    put("type", type)
    put("message", message)
}
