package com.example.visiontest.cli

import com.example.visiontest.ServerNotRunningException
import com.example.visiontest.common.RequestDeadline
import com.example.visiontest.recording.RecordingExit
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FlowRecordingEvidenceTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `returned action and final observation failures remain primary during recorder failure`() {
        for (platform in Platform.entries) for (source in EvidenceFailureSource.entries) {
            val artifacts = directory.resolve("${platform.value}-$source")
            val session = FakeRecordingSession(artifacts).also { it.platform = platform }
            val (report, requests) = recordingFailureDuringEvidence(platform, session, artifacts,
                RecordingEvidenceCase(source = source))
            assertEquals(if (source == EvidenceFailureSource.THROWN_ACTION) 3 else 1, report.exitCode)
            assertEquals(source.message, report.error?.message)
            assertEquals("failed", report.recording?.status)
            assertEquals("record", report.recording?.phase)
            assertEquals("succeeded", report.evidence.single().status)
            assertEquals(1, requests)
            if (source == EvidenceFailureSource.FINAL_OBSERVATION) {
                assertTrue(report.steps.all { it.status == "succeeded" })
                assertEquals(listOf("failed", "skipped"), report.observations.map { it.status })
            } else {
                assertEquals(listOf("failed", "skipped"), report.steps.map { it.status })
                assertTrue(report.observations.all { it.status == "skipped" })
            }
        }
    }

    @Test
    fun `evidence failure cannot replace the action failure with either healthy or failed recording`() {
        for (platform in Platform.entries) for (recorderFails in listOf(false, true)) {
            val artifacts = directory.resolve("${platform.value}-$recorderFails")
            val session = FakeRecordingSession(artifacts).also { it.platform = platform }
            val (report, requests) = recordingFailureDuringEvidence(platform, session, artifacts,
                RecordingEvidenceCase(evidenceFails = true, recorderFails = recorderFails))
            assertEquals(3, report.exitCode)
            assertEquals("Action backend lost", report.error?.message)
            assertEquals(if (recorderFails) "failed" else "succeeded", report.recording?.status)
            assertEquals("failed", report.evidence.single().status)
            assertTrue(report.evidence.single().error?.message.orEmpty().contains("Evidence unavailable"))
            assertEquals(1, requests)
            assertEquals(listOf("record-start", "record-stop"), session.events)
        }
    }

    @Test
    fun `healthy recorder stays active through successful evidence`() {
        for (platform in Platform.entries) {
            val artifacts = directory.resolve(platform.value)
            val session = FakeRecordingSession(artifacts).also { it.platform = platform }
            val (report, requests) = recordingFailureDuringEvidence(platform, session, artifacts,
                RecordingEvidenceCase(recorderFails = false))
            assertEquals(3, report.exitCode)
            assertEquals("Action backend lost", report.error?.message)
            assertEquals("succeeded", report.recording?.status)
            assertEquals("succeeded", report.evidence.single().status)
            assertEquals(1, requests)
            assertEquals(listOf("record-start", "record-stop"), session.events)
        }
    }

    @Test
    fun `recorder failure discovered during finalization never reruns completed evidence`() {
        for (platform in Platform.entries) {
            val artifacts = directory.resolve(platform.value)
            val session = FakeRecordingSession(artifacts).also { it.platform = platform }
            session.beforeFinish = { session.terminal.complete(RecordingExit(7, "discovered during finalization")) }
            val (report, requests) = recordingFailureDuringEvidence(platform, session, artifacts,
                RecordingEvidenceCase(recorderFails = false))
            assertEquals(3, report.exitCode)
            assertEquals("Action backend lost", report.error?.message)
            assertEquals("failed", report.recording?.status)
            assertEquals("record", report.recording?.phase)
            assertEquals("succeeded", report.evidence.single().status)
            assertEquals(1, requests)
            assertEquals(listOf("record-start", "record-stop"), session.events)
        }
    }

    @Test
    fun `recorder exit leaves active evidence on its original independent deadline`() = runTest {
        for (platform in Platform.entries) {
            val session = FakeRecordingSession(directory).also { it.platform = platform }
            val components = mockk<ComponentHolder>(relaxed = true)
            var requests = 0
            val evidence: suspend () -> String = { requests++; awaitCancellation() }
            coEvery { components.androidAutomationRegistrar.getUiHierarchy() } coAnswers { evidence() }
            coEvery { components.iosAutomationRegistrar.getUiHierarchy(null) } coAnswers { evidence() }
            val startedAt = testScheduler.currentTime
            val execution = async {
                FlowExecutor { RequestDeadline(it) { testScheduler.currentTime * 1_000_000 } }
                    .execute(platform, listOf(FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                        throw ServerNotRunningException("Action backend lost")
                    }), observationOptions = FlowObservationOptions(
                        collector = FlowObservationCollector(platform, lazy { components }),
                        onFailure = listOf("hierarchy", "interactive-elements"), evidenceTimeoutMs = 100),
                        recordingOptions = options(session))
            }
            runCurrent()
            assertEquals(1, requests)
            advanceTimeBy(40)
            session.terminal.complete(RecordingExit(7, "during evidence"))
            runCurrent()
            assertFalse(execution.isCompleted)
            assertEquals(listOf("record-start"), session.events)
            advanceTimeBy(60)
            runCurrent()
            val report = execution.await()
            assertEquals(startedAt + 100, testScheduler.currentTime)
            assertEquals(3, report.exitCode)
            assertEquals("Action backend lost", report.error?.message)
            assertEquals(listOf("failed", "skipped"), report.evidence.map { it.status })
            assertEquals("TimeoutException", report.evidence.first().error?.type)
            assertEquals("record", report.recording?.phase)
            assertEquals(1, requests)
        }
    }

    @Test
    fun `recorder first stops execution before collecting failed evidence once`() = runTest {
        for (platform in Platform.entries) {
            val session = FakeRecordingSession(directory).also { it.platform = platform }
            val components = mockk<ComponentHolder>(relaxed = true)
            val actionStarted = CompletableDeferred<Unit>()
            var actionStopped = false
            var requests = 0
            val evidence: suspend () -> String = {
                assertTrue(actionStopped)
                requests++
                throw ServerNotRunningException("Evidence backend lost")
            }
            coEvery { components.androidAutomationRegistrar.getUiHierarchy() } coAnswers { evidence() }
            coEvery { components.iosAutomationRegistrar.getUiHierarchy(null) } coAnswers { evidence() }
            val execution = async {
                FlowExecutor().execute(platform, listOf(
                    FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                        actionStarted.complete(Unit)
                        try { awaitCancellation() } finally { actionStopped = true }
                    }, FlowPreparedStep("step-2", "press_home", FlowStepKind.ACTION) {
                        error("Later action must not run")
                    }), observationOptions = FlowObservationOptions(
                    collector = FlowObservationCollector(platform, lazy { components }),
                    observe = listOf("interactive-elements"), onFailure = listOf("hierarchy")),
                    recordingOptions = options(session))
            }
            actionStarted.await()
            session.terminal.complete(RecordingExit(7, "recorder failed first"))
            val report = execution.await()
            assertEquals(1, report.exitCode)
            assertEquals(report.recording?.error, report.error)
            assertEquals(listOf("failed", "skipped"), report.steps.map { it.status })
            assertEquals("skipped", report.observations.single().status)
            assertEquals("failed", report.evidence.single().status)
            assertEquals("Evidence backend lost", report.evidence.single().error?.message)
            assertEquals(1, requests)
        }
    }

    @Test
    fun `recorder failure at the final observation handoff collects evidence once`() = runTest {
        for (platform in Platform.entries) {
            val session = FakeRecordingSession(directory).also { it.platform = platform }
            val components = mockk<ComponentHolder>(relaxed = true)
            val handoff = CompletableDeferred<Unit>()
            var observations = 0
            var evidenceRequests = 0
            val finalObservation: suspend () -> String = {
                observations++
                CoroutineScope(currentCoroutineContext()).launch { handoff.await() }
                """{"result":{"success":true,"count":0,"elements":[]}}"""
            }
            coEvery { components.androidAutomationRegistrar.getInteractiveElements(options = any()) } coAnswers {
                finalObservation()
            }
            coEvery {
                components.iosAutomationRegistrar.getInteractiveElements(bundleId = null, options = any())
            } coAnswers {
                finalObservation()
            }
            val evidence: suspend () -> String = {
                evidenceRequests++
                """{"result":{"success":false,"error":"Evidence unavailable"}}"""
            }
            coEvery { components.androidAutomationRegistrar.getUiHierarchy() } coAnswers { evidence() }
            coEvery { components.iosAutomationRegistrar.getUiHierarchy(null) } coAnswers { evidence() }
            val execution = async {
                FlowExecutor().execute(platform, listOf(successfulStep()), observationOptions = FlowObservationOptions(
                    collector = FlowObservationCollector(platform, lazy { components }, artifacts = directory),
                    observe = listOf("interactive-elements"), onFailure = listOf("hierarchy")),
                    recordingOptions = options(session))
            }
            runCurrent()
            assertEquals(1, observations)
            assertFalse(execution.isCompleted)
            session.terminal.complete(RecordingExit(7, "at final observation handoff"))
            runCurrent()
            val report = execution.await()
            assertEquals("succeeded", report.observations.single().status)
            assertEquals("failed", report.evidence.single().status)
            assertEquals(report.recording?.error, report.error)
            assertEquals(1, evidenceRequests)
        }
    }

    private fun successfulStep() = FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
        """{"result":{"success":true}}"""
    }

    private fun options(session: FakeRecordingSession) = FlowRecordingOptions(directory,
        lazy { FlowRecordingFactory { _, _, _, _ -> session } })
}

internal enum class EvidenceFailureSource(val message: String) {
    THROWN_ACTION("Action backend lost"),
    RETURNED_ACTION("Action rejected"),
    FINAL_OBSERVATION("Observation unavailable"),
}

internal data class RecordingEvidenceCase(
    val source: EvidenceFailureSource = EvidenceFailureSource.THROWN_ACTION,
    val evidenceFails: Boolean = false,
    val recorderFails: Boolean = true,
)

internal fun recordingFailureDuringEvidence(
    platform: Platform,
    session: FakeRecordingSession,
    artifacts: Path,
    case: RecordingEvidenceCase = RecordingEvidenceCase(),
): Pair<FlowReport, Int> = FlowObservationFixture(platform).use { fixture ->
    val observing = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    fixture.respond = { method ->
        if (method == "ui.dumpHierarchy") {
            MockResponse().setBody("""{"result":{"success":false,"error":"Observation unavailable"}}""")
        } else {
            observing.complete(Unit)
            release.await()
            MockResponse().setBody(if (case.evidenceFails) {
                """{"result":{"success":false,"error":"Evidence unavailable"}}"""
            } else """{"result":{"success":true,"pngBase64":"AQID"}}""")
        }
    }
    try {
        runBlocking {
            val execution = async {
                FlowExecutor().execute(platform, listOf(
                    FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                        assertEquals(listOf("record-start"), session.events)
                        when (case.source) {
                            EvidenceFailureSource.THROWN_ACTION -> throw ServerNotRunningException(case.source.message)
                            EvidenceFailureSource.RETURNED_ACTION ->
                                """{"result":{"success":false,"error":"Action rejected"}}"""
                            EvidenceFailureSource.FINAL_OBSERVATION -> """{"result":{"success":true}}"""
                        }
                    },
                    FlowPreparedStep("step-2", "press_home", FlowStepKind.ACTION) {
                        check(case.source == EvidenceFailureSource.FINAL_OBSERVATION)
                        """{"result":{"success":true}}"""
                    }), observationOptions = FlowObservationOptions(
                    collector = FlowObservationCollector(platform, fixture.components, artifacts = artifacts),
                    observe = listOf("hierarchy", "interactive-elements"), onFailure = listOf("screenshot")),
                    recordingOptions = FlowRecordingOptions(artifacts,
                        lazy { FlowRecordingFactory { _, _, _, _ -> session } }))
            }
            withTimeout(5000) { observing.await() }
            assertEquals(listOf("record-start"), session.events)
            if (case.recorderFails) session.terminal.complete(RecordingExit(7, "evidence encoder failure"))
            assertFalse(execution.isCompleted)
            release.countDown()
            val report = withTimeout(5000) { execution.await() }
            report to fixture.calls.count { it == "ui.screenshot" }
        }
    } finally {
        release.countDown()
    }
}
