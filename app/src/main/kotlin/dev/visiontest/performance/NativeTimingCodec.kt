package dev.visiontest.performance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.util.Base64

internal enum class NativeTimingStatus { AVAILABLE, MISSING, INVALID, MISMATCHED, UNSUPPORTED }

internal enum class NativeStage(val wireName: String) {
    REQUEST("request"), DECODE("decode"), QUEUE_WAIT("queue_wait"), OPERATION("operation"),
    UI_SYNC("ui_sync"), POLL("poll"), POLL_WAIT("poll_wait"), SCREENSHOT_CAPTURE("screenshot_capture"),
    PNG_ENCODE("png_encode"), BASE64_ENCODE("base64_encode"), RESPONSE_ENCODE("response_encode"),
    SCREENSHOT_CAPTURE_ENCODE("screenshot_capture_encode"),
}

internal data class NativeSpan(
    val spanId: String,
    val parentSpanId: String?,
    val stage: NativeStage,
    val startOffsetNs: Long,
    val durationNs: Long,
    val outcome: TraceOutcome,
    val metrics: Map<TraceMetric, Long> = emptyMap(),
) {
    val clockDomain: String get() = "native.request"
}

internal data class NativeTimingResult(
    val status: NativeTimingStatus,
    val spans: List<NativeSpan> = emptyList(),
    val missingStages: List<NativeStage> = emptyList(),
)

private const val MAX_HEADER_LENGTH = 8192
private const val MAX_NATIVE_SPANS = 32
private const val MAX_JSON_DEPTH = 16
private val identifier = Regex("[0-9a-f]{32}")
private val nonnegativeInteger = Regex("0|[1-9][0-9]*")
private val base64url = Regex("[A-Za-z0-9_-]+={0,2}")

internal fun encodeTraceRequest(invocationId: String, requestSpanId: String): String {
    require(identifier.matches(invocationId) && identifier.matches(requestSpanId))
    return "v1;$invocationId;$requestSpanId"
}

/** Optional, untrusted response metadata never changes the transport result. */
internal fun decodeNativeTiming(value: String?, invocationId: String, requestSpanId: String): NativeTimingResult {
    if (value == null) return NativeTimingResult(NativeTimingStatus.MISSING)
    return runCatching {
        require(value.length <= MAX_HEADER_LENGTH && base64url.matches(value))
        val bytes = Base64.getUrlDecoder().decode(value)
        val json = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        validateJsonDepth(json)
        val body = Json.parseToJsonElement(json) as? JsonObject ?: error("object required")
        decodeEnvelope(body, invocationId, requestSpanId)
    }.getOrElse { NativeTimingResult(NativeTimingStatus.INVALID) }
}

private fun decodeEnvelope(body: JsonObject, invocationId: String, requestSpanId: String): NativeTimingResult {
    val version = body.number("version")
    return if (version != 1L) {
        NativeTimingResult(NativeTimingStatus.UNSUPPORTED)
    } else {
        val actualInvocation = body.id("invocationId")
        val actualRequest = body.id("requestSpanId")
        if (actualInvocation != invocationId || actualRequest != requestSpanId) {
            NativeTimingResult(NativeTimingStatus.MISMATCHED)
        } else {
            decodeSnapshot(body)
        }
    }
}

private fun decodeSnapshot(body: JsonObject): NativeTimingResult {
    require(body.keys == setOf("version", "invocationId", "requestSpanId", "spans", "missingStages"))
    val values = body["spans"] as? JsonArray ?: error("spans required")
    require(values.size <= MAX_NATIVE_SPANS)
    val spans = values.map { decodeSpan(it as? JsonObject ?: error("span object required")) }
    validateParents(spans)
    val missing = body["missingStages"] as? JsonArray ?: error("missing stages required")
    require(missing.size <= NativeStage.entries.size)
    val stages = missing.map { value ->
        val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        NativeStage.entries.single { it.wireName == name }
    }
    require(stages.distinct().size == stages.size)
    return NativeTimingResult(NativeTimingStatus.AVAILABLE, spans, stages)
}

private fun decodeSpan(body: JsonObject): NativeSpan {
    val required = setOf("spanId", "parentSpanId", "stage", "startOffsetNs", "durationNs", "outcome")
    require(body.keys.containsAll(required) && body.keys.all { it in required || it == "metrics" })
    val parent = if (body["parentSpanId"] == JsonNull) null else body.id("parentSpanId")
    val offset = body.number("startOffsetNs")
    val duration = body.number("durationNs")
    require(offset <= Long.MAX_VALUE - duration)
    val metrics = body["metrics"]?.let { value ->
        val objectValue = value as? JsonObject ?: error("metric object required")
        objectValue.map { (key, _) ->
            TraceMetric.entries.single { it.wireName == key } to objectValue.number(key)
        }.toMap()
    } ?: emptyMap()
    return NativeSpan(
        body.id("spanId"), parent,
        NativeStage.entries.single { it.wireName == body.string("stage") },
        offset, duration,
        TraceOutcome.entries.single { it.wireName == body.string("outcome") }, metrics,
    )
}

private fun validateParents(spans: List<NativeSpan>) {
    val byId = spans.associateBy { it.spanId }
    require(byId.size == spans.size)
    for (span in spans) {
        val visited = mutableSetOf(span.spanId)
        var child = span
        while (child.parentSpanId != null) {
            val parent = byId[child.parentSpanId] ?: error("unknown parent")
            require(visited.add(parent.spanId))
            require(child.startOffsetNs >= parent.startOffsetNs)
            require(child.startOffsetNs + child.durationNs <= parent.startOffsetNs + parent.durationNs)
            child = parent
        }
    }
}

private fun JsonObject.string(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("string required")

private fun JsonObject.id(key: String): String = string(key).also { require(identifier.matches(it)) }

private fun JsonObject.number(key: String): Long {
    val value = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.content ?: error("number required")
    require(nonnegativeInteger.matches(value))
    return value.toLong()
}

/** Bound parser recursion and reject unescaped controls before allocating a JSON tree. */
private fun validateJsonDepth(json: String) {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in json) {
        require(character >= ' ' || (!quoted && character in "\t\r\n"))
        if (escaped) {
            escaped = false
            continue
        }
        when (character) {
            '\\' -> escaped = quoted
            '"' -> quoted = !quoted
            '{', '[' -> if (!quoted) depth++
            '}', ']' -> if (!quoted) depth--
        }
        require(depth in 0..MAX_JSON_DEPTH)
    }
}
