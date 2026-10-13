package com.example.visiontest.performance

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeTimingCodecTest {
    private val invocation = "a".repeat(32)
    private val request = "b".repeat(32)
    private val nativeId = "c".repeat(32)

    @Test
    fun `metadata availability distinguishes absent malformed mismatched and supported headers`() {
        assertEquals(NativeTimingStatus.MISSING, decodeNativeTiming(null, invocation, request).status)
        assertEquals(NativeTimingStatus.INVALID, decodeNativeTiming("!", invocation, request).status)
        assertEquals(NativeTimingStatus.AVAILABLE, decodeNativeTiming(header(), invocation, request).status)
        assertEquals(NativeTimingStatus.MISMATCHED,
            decodeNativeTiming(header(invocationId = "c".repeat(32)), invocation, request).status)
        assertEquals(NativeTimingStatus.MISMATCHED,
            decodeNativeTiming(header(requestId = "c".repeat(32)), invocation, request).status)
        assertEquals(NativeTimingStatus.UNSUPPORTED,
            decodeNativeTiming(header(version = 2), invocation, request).status)
        assertEquals("v1;$invocation;$request", encodeTraceRequest(invocation, request))
    }

    @Test
    fun `unsafe metadata is rejected before import`() {
        val span = span()
        val invalid = listOf(
            "a".repeat(8193),
            header(spans = "[${List(33) { span }.joinToString()}]"),
            header(spans = "[$span,$span]"),
            header(spans = "[${span(parent = nativeId)}]"),
            header(spans = "[${span(parent = "d".repeat(32))}]"),
            header(spans = "[${span().replace("\"durationNs\":10", "\"durationNs\":-1")}]"),
            header(spans = "[${span().replace("\"durationNs\":10", "\"durationNs\":9223372036854775808")}]"),
            header(spans = "[${span().replace("\"startOffsetNs\":0", "\"startOffsetNs\":9223372036854775807")}]"),
            header(spans = "[${span().replace("\"request\"", "\"arbitrary\"")}]"),
            header(spans = "[${span().replace("\"returned\"", "\"success\"")}]"),
            header(spans = "[${span().replace(nativeId, "not-an-id")}]"),
            header(spans = "[${span(metrics = """{"pollCount":"1"}""")}]"),
            header(spans = "[${span(metrics = """{"pollCount":1.5}""")}]"),
            header(spans = "[${span(metrics = """{"pollCount":true}""")}]"),
            header(spans = "[${span(metrics = """{"pollCount":-1}""")}]"),
            header(spans = "[${span(metrics = """{"pollCount":9223372036854775808}""")}]"),
            header(spans = "[${span(metrics = """{"secret":1}""")}]"),
            header(missing = "[\"secret\"]"),
            encode("""{"version":"1","invocationId":"$invocation","requestSpanId":"$request",""" +
                """"spans":[],"missingStages":[]}"""),
            encode("{}"),
            header(invocationId = "A".repeat(32)),
            header(spans = "{}"),
            header(missing = "[1]"),
            header(missing = "[\"poll\",\"poll\"]"),
            header(spans = "[${span().replace("\"startOffsetNs\":0", "\"startOffsetNs\":-1")}]"),
            header(spans = "[${span().replace("\"durationNs\":10", "\"durationNs\":\"10\"")}]"),
            header(spans = "[${span().replace("\"stage\":\"request\"", "\"stage\":1")}]"),
            header(spans = "[${span().replace("\"parentSpanId\":null,", "")}]"),
            encode("[".repeat(1000) + "]".repeat(1000)),
        )
        invalid.forEachIndexed { index, value ->
            assertEquals(NativeTimingStatus.INVALID,
                decodeNativeTiming(value, invocation, request).status, "case $index")
            assertTrue(decodeNativeTiming(value, invocation, request).spans.isEmpty(), "case $index")
        }
        val parent = span()
        val outside = span(id = "d".repeat(32), parent = nativeId)
            .replace("\"startOffsetNs\":0", "\"startOffsetNs\":1")
        assertEquals(NativeTimingStatus.INVALID,
            decodeNativeTiming(header(spans = "[$parent,$outside]"), invocation, request).status)
        val cycle = "[${span(parent = "d".repeat(32))}," +
            "${span(id = "d".repeat(32), parent = nativeId)}]"
        assertEquals(NativeTimingStatus.INVALID, decodeNativeTiming(header(spans = cycle), invocation, request).status)
        val invalidUtf8 = Base64.getUrlEncoder().encodeToString(byteArrayOf(0xc3.toByte(), 0x28))
        assertEquals(NativeTimingStatus.INVALID, decodeNativeTiming(invalidUtf8, invocation, request).status)
    }

    @Test
    fun `valid metadata preserves native offsets hierarchy metrics and missing stages`() {
        val parent = span(
            metrics = """{"pollCount":2,"waitNs":3,"requestBytes":4,"responseBytes":5,"decodedBytes":6}""")
        val child = span(id = "d".repeat(32), parent = nativeId).replace("\"request\"", "\"decode\"")
        val result = decodeNativeTiming(
            header(spans = "[$parent,$child]", missing = "[\"ui_sync\"]"), invocation, request)
        assertEquals(NativeTimingStatus.AVAILABLE, result.status)
        assertEquals(2, result.spans.size)
        assertEquals(nativeId, result.spans[1].parentSpanId)
        assertEquals(NativeStage.DECODE, result.spans[1].stage)
        assertEquals("native.request", result.spans[1].clockDomain)
        assertEquals(0L, result.spans[1].startOffsetNs)
        assertEquals(10L, result.spans[1].durationNs)
        assertEquals(TraceOutcome.RETURNED, result.spans[1].outcome)
        assertEquals(5, result.spans[0].metrics.size)
        assertEquals(2L, result.spans[0].metrics[TraceMetric.POLL_COUNT])
        assertEquals(listOf(NativeStage.UI_SYNC), result.missingStages)
        val maximum = span().replace("\"durationNs\":10", "\"durationNs\":9223372036854775807")
        assertEquals(Long.MAX_VALUE,
            decodeNativeTiming(header(spans = "[$maximum]"), invocation, request).spans.single().durationNs)
    }

    @Test
    fun `trace request encoder rejects identifiers outside the wire grammar`() {
        for ((invocationId, requestId) in listOf("A".repeat(32) to request, invocation to "short", "" to request)) {
            assertFailsWith<IllegalArgumentException> { encodeTraceRequest(invocationId, requestId) }
        }
    }

    private fun span(id: String = nativeId, parent: String? = null, metrics: String = "{}"): String =
        """{"spanId":"$id","parentSpanId":${parent?.let { "\"$it\"" } ?: "null"},"stage":"request",""" +
            """"startOffsetNs":0,"durationNs":10,"outcome":"returned","metrics":$metrics}"""

    private fun header(
        invocationId: String = invocation,
        requestId: String = request,
        version: Int = 1,
        spans: String = "[]",
        missing: String = "[]",
    ): String = encode("""{"version":$version,"invocationId":"$invocationId","requestSpanId":"$requestId",""" +
        """"spans":$spans,"missingStages":$missing}""")

    private fun encode(json: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.toByteArray(Charsets.UTF_8))
}
