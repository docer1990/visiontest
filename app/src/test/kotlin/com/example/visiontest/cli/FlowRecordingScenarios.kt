package com.example.visiontest.cli

import com.example.visiontest.common.RequestDeadline
import com.example.visiontest.recording.RecordingContainerInspector
import com.example.visiontest.recording.RecordingException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FlowRecordingScenarios(private val platform: Platform, private val artifacts: Path) {
    private val session = FakeRecordingSession(artifacts).also { it.platform = platform }
    private var report: FlowReport? = null
    private var lines = emptyList<String>()
    private var interruption: RecordingInterruptionAttempt? = null
    private var evidenceRequests = 0

    fun bindings(): Map<String, () -> Unit> =
        successBindings() + startupFailureBindings() + interruptionBindings() + evidenceBindings() +
            finalizationBindings()

    private fun finalizationBindings(): Map<String, () -> Unit> = mapOf(
        "Given a recorded flow whose steps have succeeded" to { lines = listOf("press_home", "press_home") },
        "When the video cannot be saved correctly" to {
            session.finishFailure = RecordingException("Video transfer failed")
            FlowObservationFixture(platform).use { fixture ->
                val steps = FlowPreparation.prepare(platform, lines, fixture.components)
                report = runBlocking {
                    FlowExecutor().execute(platform, steps, recordingOptions = FlowRecordingOptions(artifacts,
                        lazy { FlowRecordingFactory { _, _, _, _ -> session } }))
                }
            }
        },
        "And the completed steps remain successful" to {
            val actual = assertNotNull(report)
            assertEquals(2, actual.steps.size)
            assertTrue(actual.steps.all { it.status == "succeeded" })
            assertNull(actual.failedStep)
        },
        "And no invalid video path is reported" to {
            val actual = assertNotNull(report)
            assertNull(actual.recording?.path)
            assertTrue(actual.text().contains("Video transfer failed"))
        },
    )

    private fun evidenceBindings(): Map<String, () -> Unit> = mapOf(
        "Given a recorded flow whose first action has failed" to { lines = listOf("press_home", "press_home") },
        "When the recorder also fails during failure evidence collection" to {
            assertEquals(2, lines.size)
            val attempt = recordingFailureDuringEvidence(platform, session, artifacts)
            report = attempt.first
            evidenceRequests = attempt.second
        },
        "Then the original action error remains the primary failure" to {
            val actual = assertNotNull(report)
            assertEquals(3, actual.exitCode)
            assertEquals("Action backend lost", actual.error?.message)
            assertEquals(listOf("failed", "skipped"), actual.steps.map { it.status })
        },
        "And the recording error is reported separately" to {
            val recording = assertNotNull(report).recording
            assertEquals("failed", recording?.status)
            assertEquals("record", recording?.phase)
            assertTrue(assertNotNull(recording?.error).message.contains("evidence encoder failure"))
        },
        "And failure evidence is collected only once" to {
            val evidence = assertNotNull(report).evidence
            assertEquals(1, evidence.size)
            assertEquals("succeeded", evidence.single().status)
            assertEquals(1, evidenceRequests)
        },
    )

    private fun interruptionBindings(): Map<String, () -> Unit> = mapOf(
        "Given a recorded flow with an action awaiting its response" to { lines = listOf("press_home", "press_home") },
        "When the recorder exits before the flow finishes" to {
            assertEquals(2, lines.size)
            interruption = interruptRecordingRequest(platform, session, artifacts, 7)
            report = interruption?.report
        },
        "And the pending action has an unknown effect" to {
            assertEquals("uncertain", assertNotNull(report).steps[0].status)
            assertEquals("unknown", assertNotNull(report).steps[0].certainty)
        },
        "And later steps are skipped without replay" to {
            assertEquals("skipped", assertNotNull(report).steps[1].status)
            assertEquals(1, assertNotNull(interruption).posts)
            assertEquals(0, assertNotNull(interruption).laterCalls)
        },
    )

    private fun successBindings(): Map<String, () -> Unit> = mapOf(
        "Given a valid flow with recording requested" to {
            lines = listOf("press_home")
            assertFalse(Files.exists(artifacts))
        },
        "When the recorder starts and the flow succeeds" to {
            FlowObservationFixture(platform).use { fixture ->
                fixture.respond = { method ->
                    assertFalse(session.terminal.isCompleted)
                    if (method == "ui.screenshot") {
                        session.events += "final-observation"
                        MockResponse().setBody("""{"result":{"success":true,"pngBase64":"AQID"}}""")
                    } else {
                        session.events += "action"
                        MockResponse().setBody("""{"result":{"success":true}}""")
                    }
                }
                val steps = FlowPreparation.prepare(platform, lines, fixture.components)
                report = runBlocking {
                    FlowExecutor().execute(platform, steps, observationOptions = FlowObservationOptions(
                        collector = FlowObservationCollector(platform, fixture.components, artifacts = artifacts),
                        observe = listOf("screenshot")), recordingOptions = FlowRecordingOptions(artifacts,
                        lazy { FlowRecordingFactory { _, _, _, _ -> session } }))
                }
            }
        },
        "Then the flow succeeds with a saved video" to {
            val actual = assertNotNull(report)
            assertTrue(actual.success)
            assertEquals(0, actual.exitCode)
            assertEquals("succeeded", actual.recording?.status)
            val path = Path.of(assertNotNull(actual.recording?.path))
            assertTrue(path.isAbsolute)
            assertTrue(Files.exists(path))
            runBlocking { RecordingContainerInspector().validateVideo(path, RequestDeadline(1000)) }
            assertEquals(setOf("status", "phase", "path", "error"),
                actual.toJson().getValue("recording").jsonObject.keys)
        },
        "And the video includes the requested final observation" to {
            assertEquals(listOf("record-start", "action", "final-observation", "record-stop"), session.events)
            val observation = assertNotNull(report).observations.single()
            assertEquals("succeeded", observation.status)
            val screenshot = Path.of(assertNotNull(observation.result).getValue("path").jsonPrimitive.content)
            assertEquals(artifacts, screenshot.parent)
            assertTrue(Files.exists(screenshot))
        },
    )

    private fun startupFailureBindings(): Map<String, () -> Unit> = mapOf(
        "When the recorder cannot start" to {
            session.startFailure = RecordingException("Encoder initialization failed")
            FlowObservationFixture(platform).use { fixture ->
                fixture.respond = { method ->
                    session.events += if (method == "ui.screenshot") "observation" else "action"
                    MockResponse().setBody("""{"result":{"success":true,"pngBase64":"AQID"}}""")
                }
                val steps = FlowPreparation.prepare(platform, lines + "press_home", fixture.components)
                report = runBlocking {
                    FlowExecutor().execute(platform, steps, observationOptions = FlowObservationOptions(
                        collector = FlowObservationCollector(platform, fixture.components, artifacts = artifacts),
                        observe = listOf("screenshot"), onFailure = listOf("screenshot")),
                        recordingOptions = FlowRecordingOptions(artifacts,
                            lazy { FlowRecordingFactory { _, _, _, _ -> session } }))
                }
            }
        },
        "Then the flow fails with the recording error" to {
            val actual = assertNotNull(report)
            assertFalse(actual.success)
            assertEquals(1, actual.exitCode)
            val phase = when {
                interruption != null -> {
                    assertTrue(assertNotNull(actual.error).message.contains("7"))
                    "record"
                }
                session.finishFailure != null -> {
                    assertEquals("Video transfer failed", actual.error?.message)
                    "finalize"
                }
                else -> {
                    assertEquals("Encoder initialization failed", actual.error?.message)
                    "start"
                }
            }
            assertEquals("failed", actual.recording?.status)
            assertEquals(phase, actual.recording?.phase)
            assertEquals(actual.error, actual.recording?.error)
        },
        "And every flow step is skipped" to {
            val steps = assertNotNull(report).steps
            assertEquals(listOf("step-1", "step-2"), steps.map { it.id })
            assertTrue(steps.all { it.status == "skipped" && it.certainty == "not_executed" })
            assertFalse(session.events.contains("action"))
        },
        "And no observations or failure evidence are collected" to {
            val actual = assertNotNull(report)
            assertEquals("skipped", actual.observations.single().status)
            assertEquals("skipped", actual.evidence.single().status)
            assertEquals(listOf("record-start", "record-stop"), session.events)
        },
    )
}
