package com.example.visiontest.cli

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowRecordingReportTest {
    @Test
    fun `successful recording adds nullable fields without changing schema version`() {
        val report = FlowReport(Platform.Android, true, 0, emptyList(),
            recording = FlowRecordingReport("succeeded", path = "/artifacts/recording.mp4"))
        val json = report.toJson()
        assertEquals("1", json.getValue("schemaVersion").toString())
        val recording = json.getValue("recording").jsonObject
        assertEquals(setOf("status", "phase", "path", "error"), recording.keys)
        assertEquals("succeeded", recording.getValue("status").jsonPrimitive.content)
        assertEquals("/artifacts/recording.mp4", recording.getValue("path").jsonPrimitive.content)
        assertEquals(JsonNull, recording.getValue("phase"))
        assertEquals(JsonNull, recording.getValue("error"))
        assertTrue(report.text().contains("Recording succeeded"))
        assertTrue(report.text().contains("/artifacts/recording.mp4"))
    }

    @Test
    fun `recording error uses the existing error shape and text includes its diagnostic`() {
        val report = FlowReport(Platform.Ios, false, 1, emptyList(), recording = FlowRecordingReport(
            "failed", "finalize", error = FlowError("RecordingException", "Unable to save video")))
        val recording = report.toJson().getValue("recording").jsonObject
        assertEquals("finalize", recording.getValue("phase").jsonPrimitive.content)
        assertEquals(JsonNull, recording.getValue("path"))
        val error = recording.getValue("error").jsonObject
        assertEquals(setOf("type", "message"), error.keys)
        assertEquals("RecordingException", error.getValue("type").jsonPrimitive.content)
        assertEquals("Unable to save video", error.getValue("message").jsonPrimitive.content)
        assertTrue(report.text().contains("Recording failed"))
        assertTrue(report.text().contains("Unable to save video"))
    }

    @Test
    fun `unrequested recording preserves the exact legacy report keys and text`() {
        val report = FlowReport(Platform.Ios, true, 0, emptyList())
        assertEquals(setOf("schemaVersion", "platform", "success", "exitCode", "steps", "failedStep",
            "skippedSteps", "observations", "evidence", "error"), report.toJson().keys)
        assertEquals("Flow succeeded: 0 completed, 0 skipped", report.text())
    }
}
