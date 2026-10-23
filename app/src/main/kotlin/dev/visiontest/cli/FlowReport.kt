package dev.visiontest.cli

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    val observations: List<FlowObservationReport> = emptyList(),
    val evidence: List<FlowObservationReport> = emptyList(),
    val recording: FlowRecordingReport? = null,
) {
    val failedStep: String? get() = steps.firstOrNull { it.status in setOf("failed", "uncertain") }?.id
    val skippedSteps: List<String> get() = steps.filter { it.status == "skipped" }.map { it.id }

    fun withRecording(recording: FlowRecordingReport?): FlowReport {
        val failure = error ?: recording?.error
        return copy(recording = recording, success = failure == null, error = failure,
            exitCode = if (success && failure != null) 1 else exitCode)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put("platform", platform.value)
        put("success", success)
        put("exitCode", exitCode)
        put("steps", buildJsonArray { steps.forEach { add(it.toJson()) } })
        put("failedStep", failedStep?.let(::JsonPrimitive) ?: JsonNull)
        put("skippedSteps", buildJsonArray { skippedSteps.forEach { add(it) } })
        put("observations", buildJsonArray { observations.forEach { add(it.toJson()) } })
        put("evidence", buildJsonArray { evidence.forEach { add(it.toJson()) } })
        put("error", error?.toJson() ?: JsonNull)
        recording?.let { put("recording", it.toJson()) }
    }

    fun text(): String = buildString {
        append("Flow ${if (success) "succeeded" else "failed"}: ")
        append("${steps.count { it.status == "succeeded" }} completed, ${skippedSteps.size} skipped")
        failedStep?.let { append("; failed step $it") }
        error?.let { append(". ${it.message}") }
        if (steps.any { it.status == "uncertain" }) {
            append(". The action effect is unknown. Inspect the UI before retrying.")
        }
        steps.mapNotNull { it.result?.get("path") as? JsonPrimitive }
            .forEach { append("\n${it.content}") }
        observations.forEach { append("\nFinal ${it.kind}: ${it.toJson()}") }
        evidence.forEach { append("\nEvidence ${it.kind}: ${it.toJson()}") }
        recording?.let {
            append("\nRecording ${it.status}")
            it.error?.let { error -> append(". ${error.message}") }
            it.path?.let { path -> append("\n$path") }
        }
    }
}

private fun FlowRecordingReport.toJson() = buildJsonObject {
    put("status", status)
    put("phase", phase?.let(::JsonPrimitive) ?: JsonNull)
    put("path", path?.let(::JsonPrimitive) ?: JsonNull)
    put("error", error?.toJson() ?: JsonNull)
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

private fun FlowObservationReport.toJson() = buildJsonObject {
    put("kind", kind)
    put("status", status)
    put("result", result ?: JsonNull)
    put("error", error?.toJson() ?: JsonNull)
}
