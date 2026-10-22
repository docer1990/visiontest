package com.example.visiontest.cli

import com.example.visiontest.cli.commands.FlowCommand
import com.example.visiontest.common.RequestDeadline
import com.example.visiontest.recording.RecordingContainerInspector
import com.example.visiontest.recording.RecordingException
import com.example.visiontest.recording.RecordingExit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FlowRecordingFinalizationTest {
    @TempDir lateinit var directory: Path

    @TestFactory
    fun `finalization errors fail the flow without rewriting successful steps`() = listOf(
        "Video transfer failed", "Invalid video container", "Video has no nonempty samples",
        "Video rename failed", "Recorder interrupt failed", "Video finalization timed out",
    ).flatMap { message -> Platform.entries.map { platform ->
        DynamicTest.dynamicTest("${platform.value} $message") { runTest {
            val failure = if (message.endsWith("timed out")) TimeoutException(message) else RecordingException(message)
            val session = FakeRecordingSession(directory).also { it.platform = platform; it.finishFailure = failure }
            val report = FlowExecutor().execute(platform, listOf(successfulStep()), recordingOptions = options(session))
            assertFalse(report.success)
            assertEquals(1, report.exitCode)
            assertEquals(listOf("succeeded"), report.steps.map { it.status })
            assertEquals("confirmed", report.steps.single().certainty)
            assertEquals("true", report.steps.single().result?.get("success").toString())
            assertNull(report.failedStep)
            assertEquals("failed", report.recording?.status)
            assertEquals("finalize", report.recording?.phase)
            assertNull(report.recording?.path)
            assertEquals(FlowError(failure.javaClass.simpleName, message), report.recording?.error)
            assertEquals(report.recording?.error, report.error)
            assertTrue(report.text().contains(message))
            assertEquals(listOf("record-start", "record-stop"), session.events)
        } }
    } }

    @Test
    fun `finalization deadline yields a report after cancelling the pending finish`() = runTest {
        var stopped = false
        val session = FakeRecordingSession(directory).also {
            it.beforeFinish = { try { awaitCancellation() } finally { stopped = true } }
        }
        val report = FlowExecutor { RequestDeadline(it) { testScheduler.currentTime * 1_000_000 } }
            .execute(Platform.Android, listOf(successfulStep()), recordingOptions = options(session))
        assertTrue(stopped)
        assertEquals(30000L, testScheduler.currentTime)
        assertFalse(report.success)
        assertEquals(1, report.exitCode)
        assertEquals("finalize", report.recording?.phase)
        assertEquals(FlowError("TimeoutException", "Flow recording finalization deadline exceeded"), report.error)
        assertEquals(listOf("succeeded"), report.steps.map { it.status })
        assertNull(report.recording?.path)
    }

    @Test
    fun `earlier action and observation errors remain primary without repeating evidence`() {
        for (platform in Platform.entries) for (source in EvidenceFailureSource.entries) {
            val artifacts = directory.resolve("${platform.value}-$source")
            val session = FakeRecordingSession(artifacts).also {
                it.platform = platform
                it.finishFailure = RecordingException("Video transfer failed")
            }
            val (report, requests) = recordingFailureDuringEvidence(platform, session, artifacts,
                RecordingEvidenceCase(source = source, recorderFails = false))
            assertEquals(source.message, report.error?.message)
            assertEquals(if (source == EvidenceFailureSource.THROWN_ACTION) 3 else 1, report.exitCode)
            assertFalse(report.success)
            assertEquals("finalize", report.recording?.phase)
            assertEquals("Video transfer failed", report.recording?.error?.message)
            assertNull(report.recording?.path)
            assertTrue(report.text().contains(source.message))
            assertTrue(report.text().contains("Video transfer failed"))
            assertEquals(1, requests)
            assertEquals("succeeded", report.evidence.single().status)
            assertEquals(if (source == EvidenceFailureSource.FINAL_OBSERVATION) listOf("succeeded", "succeeded")
                else listOf("failed", "skipped"), report.steps.map { it.status })
        }
    }

    @Test
    fun `earlier recorder failure survives a second finalization error`() {
        for (platform in Platform.entries) {
            val session = FakeRecordingSession(directory).also {
                it.platform = platform
                it.finishFailure = RecordingException("Video transfer failed")
            }
            val (report, requests) = recordingFailureDuringEvidence(platform, session, directory)
            assertEquals(3, report.exitCode)
            assertEquals("Action backend lost", report.error?.message)
            assertEquals("record", report.recording?.phase)
            assertTrue(assertNotNull(report.recording?.error).message.contains("evidence encoder failure"))
            assertNull(report.recording?.path)
            assertEquals(1, requests)
        }
    }

    @Test
    fun `premature exit discovered by finish takes precedence over artifact failure`() = runTest {
        for (platform in Platform.entries) {
            val session = FakeRecordingSession(directory).also {
                it.platform = platform
                it.beforeFinish = { it.terminal.complete(RecordingExit(7, "encoder stopped early")) }
                it.finishFailure = RecordingException("Video transfer failed")
            }
            val report = FlowExecutor().execute(platform, listOf(successfulStep()), recordingOptions = options(session))
            assertFalse(report.success)
            assertEquals(1, report.exitCode)
            assertEquals("record", report.recording?.phase)
            assertEquals(report.error, report.recording?.error)
            assertTrue(assertNotNull(report.error).message.contains("encoder stopped early"))
            assertEquals("succeeded", report.steps.single().status)
            assertNull(report.recording?.path)
        }
    }

    @Test
    fun `valid partial artifact does not turn premature exit into recording success`() = runTest {
        for (platform in Platform.entries) {
            val session = FakeRecordingSession(directory).also {
                it.platform = platform
                it.beforeFinish = { it.terminal.complete(RecordingExit(0, "encoder stopped early")) }
            }
            val report = FlowExecutor().execute(platform, listOf(successfulStep()), recordingOptions = options(session))
            assertFalse(report.success)
            assertEquals(1, report.exitCode)
            assertEquals("failed", report.recording?.status)
            assertEquals("record", report.recording?.phase)
            val path = Path.of(assertNotNull(report.recording?.path))
            assertTrue(path.isAbsolute)
            RecordingContainerInspector().validateVideo(path, RequestDeadline(1000))
            assertEquals("succeeded", report.steps.single().status)
        }
    }

    @Test
    fun `CLI retains one structured report and stderr diagnostics without publishing a remote artifact`() {
        for (platform in Platform.entries) for (json in listOf(false, true)) {
            FlowObservationFixture(platform).use { fixture ->
                val diagnostic = "Video transfer failed; recover /sdcard/owned-recording.mp4"
                val session = FakeRecordingSession(directory).also { it.finishFailure = RecordingException(diagnostic) }
                var result: CliResult? = null
                FlowCommand(fixture.components, runner = { result = executeCliResultCommand(it) },
                    recordingFactory = lazy { FlowRecordingFactory { _, _, _, _ -> session } })
                    .parse(listOf("-p", platform.value, "--record", "--step", "press_home",
                        "--on-failure", "screenshot") + if (json) listOf("--json") else emptyList())
                assertCliOutput(assertNotNull(result), diagnostic, json)
                assertFalse(fixture.calls.contains("ui.screenshot"))
            }
        }
    }

    private fun assertCliOutput(actual: CliResult, diagnostic: String, json: Boolean) {
        assertEquals(1, actual.exitCode)
        assertEquals(diagnostic, actual.stderr)
        val stdout = assertNotNull(actual.stdout)
        if (json) {
            val report = Json.parseToJsonElement(stdout).jsonObject
            val recording = report.getValue("recording").jsonObject
            assertEquals(1, "\"recording\":".toRegex().findAll(stdout).count())
            assertEquals(setOf("status", "phase", "path", "error"), recording.keys)
            assertEquals(JsonNull, recording["path"])
            assertEquals(diagnostic, recording.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
            assertEquals("skipped", report.getValue("evidence").jsonArray.single().jsonObject
                .getValue("status").jsonPrimitive.content)
            assertFalse(report.containsKey("jsonrpc"))
        } else {
            assertTrue(stdout.startsWith("Flow failed: 1 completed, 0 skipped"))
            assertTrue(stdout.contains("Recording failed"))
            assertTrue(stdout.contains(diagnostic))
        }
    }

    private fun successfulStep() = FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
        """{"result":{"success":true}}"""
    }

    private fun options(session: FakeRecordingSession) = FlowRecordingOptions(directory,
        lazy { FlowRecordingFactory { _, _, _, _ -> session } })
}
