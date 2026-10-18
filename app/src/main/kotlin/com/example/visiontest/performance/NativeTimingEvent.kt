package com.example.visiontest.performance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One request's validated device-clock measurements, never a host-clock span. */
internal data class NativeTimingEvent(
    val sessionId: String,
    val invocationId: String,
    val invocationSequence: Long,
    val requestSpanId: String,
    val platform: String?,
    val operation: String,
    val timing: NativeTimingResult,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", "native.timing")
        put("sessionId", sessionId)
        put("invocationId", invocationId)
        put("invocationSequence", invocationSequence)
        put("requestSpanId", requestSpanId)
        put("process", platform?.let { JsonPrimitive(it) } ?: JsonNull)
        put("platform", platform?.let { JsonPrimitive(it) } ?: JsonNull)
        put("operation", operation)
        put("clockDomain", "native.request")
        put("status", timing.status.name.lowercase())
        put("missingStages", JsonArray(timing.missingStages.map { JsonPrimitive(it.wireName) }))
        put("spans", JsonArray(timing.spans.map { span ->
            buildJsonObject {
                put("spanId", span.spanId)
                put("parentSpanId", span.parentSpanId?.let { JsonPrimitive(it) } ?: JsonNull)
                put("stage", span.stage.wireName)
                put("startOffsetNs", span.startOffsetNs)
                put("durationNs", span.durationNs)
                put("outcome", span.outcome.wireName)
                if (span.metrics.isNotEmpty()) {
                    putJsonObject("metrics") { span.metrics.forEach { (key, value) -> put(key.wireName, value) } }
                }
            }
        }))
    }
}

internal class NativeTimingRequest(val header: String, private val emit: (String?) -> Unit) {
    fun record(value: String?) {
        runCatching { emit(value) }
    }
}
