package com.example.visiontest.cli

import com.example.visiontest.NoDeviceAvailableException
import com.example.visiontest.NoSimulatorAvailableException
import com.example.visiontest.cli.commands.FlowCommand
import com.example.visiontest.common.RequestDeadline
import com.example.visiontest.recording.RecordingException
import com.example.visiontest.recording.RecordingSession
import com.example.visiontest.recording.AndroidRecordingSession
import com.example.visiontest.recording.FakeAndroidRecordingProcesses
import com.example.visiontest.recording.RecordingProcessRunner
import com.example.visiontest.recording.RecordingCommandResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FlowRecordingStartupTest {
    @TempDir lateinit var directory: Path

    @TestFactory
    fun `startup failures retain errors and skip every requested operation`() = cases(
        listOf("missing-device", "missing-simulator", "missing-tools", "unknown-duration",
            "excess-duration", "unwritable-destination")) { kind ->
        val failure = when (kind) {
            "missing-device" -> NoDeviceAvailableException("No Android device available")
            "missing-simulator" -> NoSimulatorAvailableException("No iOS simulator available")
            "missing-tools" -> RecordingException("adb is unavailable")
            "unknown-duration" -> RecordingException("Cannot establish screenrecord duration capability")
            "excess-duration" -> IllegalArgumentException("Supported duration 180s cannot cover requested 185s")
            else -> AccessDeniedException("Recording destination is not writable")
        }
        val fake = FakeRecordingSession(directory).also { it.startFailure = failure }
        val report = FlowExecutor().execute(Platform.Android, steps(fake.events),
            observationOptions = observations(fake.events), recordingOptions = options(fake))
        assertRejected(report, failure.message!!,
            if (kind in listOf("missing-device", "missing-simulator")) 4 else 1)
        assertEquals(listOf("record-start", "record-stop"), fake.events)
    }

    @TestFactory
    fun `factory failures produce one complete command report after execution begins`() = cases(
        listOf("factory-initialization", "session-allocation", "component-creation")) { kind ->
        val events = mutableListOf<String>()
        val components = lazy<ComponentHolder> { events += "components"; error("Component creation failed") }
        val factory = lazy {
            if (kind == "factory-initialization") error("Factory initialization failed")
            FlowRecordingFactory { _, _, _, _ ->
                if (kind == "component-creation") components.value
                error("Session allocation failed")
            }
        }
        var result: CliResult? = null
        FlowCommand(components, runner = { result = executeCliResultCommand(it) }, recordingFactory = factory)
            .parse(listOf("--platform", "android", "--record", "--json", "--step", "press_home",
                "--observe", "screenshot", "--on-failure", "screenshot"))
        val actual = assertNotNull(result)
        assertEquals(1, actual.exitCode)
        val report = Json.parseToJsonElement(assertNotNull(actual.stdout)).jsonObject
        assertEquals("false", report.getValue("success").toString())
        val primary = report.getValue("error").jsonObject.getValue("message").jsonPrimitive.content
        assertTrue(assertNotNull(actual.stderr).startsWith(primary))
        assertTrue(actual.stderr.contains(report.getValue("recording").jsonObject.getValue("error")
            .jsonObject.getValue("message").jsonPrimitive.content))
        assertEquals("start", report.getValue("recording").jsonObject.getValue("phase").jsonPrimitive.content)
        for (field in listOf("steps", "observations", "evidence")) {
            assertEquals("skipped", report.getValue(field).jsonArray.single().jsonObject
                .getValue("status").jsonPrimitive.content)
        }
        assertEquals(if (kind == "component-creation") listOf("components") else emptyList(), events)
    }

    @TestFactory
    fun `startup waits only for remaining execution budget or startup ceiling`() = cases(
        listOf(5L, 10000L)) { remaining ->
        val timeout = if (remaining == 5L) 500L else 120000L
        val fake = FakeRecordingSession(directory)
        var startupEnded = false
        val session = object : RecordingSession by fake {
            override suspend fun start(deadline: RequestDeadline) {
                assertEquals(remaining, deadline.remainingMillis())
                try { awaitCancellation() } finally { startupEnded = true }
            }
        }
        var firstDeadline = true
        val executor = FlowExecutor { budget ->
            RequestDeadline(budget) { testScheduler.currentTime * 1_000_000 }.also {
                if (firstDeadline) {
                    firstDeadline = false
                    if (remaining == 5L) testScheduler.advanceTimeBy(timeout - remaining)
                }
            }
        }
        fake.beforeFinish = { delay(20) }
        val report = executor.execute(Platform.Android, steps(fake.events), timeoutMs = timeout,
            observationOptions = observations(fake.events), recordingOptions = options(session))
        assertRejected(report, "Flow recording startup deadline exceeded")
        assertTrue(startupEnded)
        assertEquals((if (remaining == 5L) timeout else remaining) + 20, testScheduler.currentTime)
        assertEquals(listOf("record-stop"), fake.events)
    }

    @TestFactory
    fun `synchronous startup work cannot dispatch actions after its deadline`() = cases(
        listOf("factory", "start")) { phase ->
        var now = 0L
        val fake = FakeRecordingSession(directory)
        val session = object : RecordingSession by fake {
            override suspend fun start(deadline: RequestDeadline) {
                fake.start(deadline)
                now += 10000
            }
        }
        val factory = lazy { FlowRecordingFactory { _, _, _, _ ->
            if (phase == "factory") now += 10000
            session
        } }
        val report = FlowExecutor { RequestDeadline(it) { now * 1_000_000 } }.execute(
            Platform.Android, steps(fake.events), observationOptions = observations(fake.events),
            recordingOptions = FlowRecordingOptions(directory, factory))
        assertRejected(report, "Flow execution deadline exceeded")
        assertEquals(if (phase == "factory") listOf("record-stop") else listOf("record-start", "record-stop"),
            fake.events)
    }

    @TestFactory
    fun `startup cleanup has its own bounded budget and cannot replace the original failure`() = cases(
        listOf(false, true)) { hang ->
        val fake = FakeRecordingSession(directory).also {
            it.startFailure = RecordingException("Encoder initialization failed")
            it.finishFailure = RecordingException("Cleanup failed")
        }
        var cleanupEnded = false
        val session = object : RecordingSession by fake {
            override suspend fun finish(deadline: RequestDeadline): Path {
                assertEquals(30000L, deadline.remainingMillis())
                try {
                    if (hang) awaitCancellation()
                    return fake.finish(deadline)
                } finally { cleanupEnded = true }
            }
        }
        val report = FlowExecutor { RequestDeadline(it) { testScheduler.currentTime * 1_000_000 } }.execute(
            Platform.Android, steps(fake.events), timeoutMs = 5,
            observationOptions = observations(fake.events), recordingOptions = options(session))
        assertRejected(report, "Encoder initialization failed", cleanupMessage =
            if (hang) "Flow recording finalization deadline exceeded" else "Cleanup failed")
        assertTrue(cleanupEnded)
        assertEquals(if (hang) 30000L else 0L, testScheduler.currentTime)
    }

    @TestFactory
    fun `Android startup failure retains video recovery diagnostics in command output`() = cases(
        listOf(false, true)) { json ->
        val processes = FakeAndroidRecordingProcesses().also { it.pullCode = 1 }
        val runner = object : RecordingProcessRunner by processes {
            override suspend fun run(command: List<String>, deadline: RequestDeadline, stdoutLimitBytes: Int)
                : RecordingCommandResult {
                if (command.last().contains("then dd if=")) throw RecordingException("Readiness inspection failed")
                return processes.run(command, deadline, stdoutLimitBytes)
            }
        }
        var result: CliResult? = null
        FlowCommand(lazy { error("Startup failure must skip device operations") },
            runner = { result = executeCliResultCommand(it) },
            recordingFactory = lazy { FlowRecordingFactory { scope, _, _, duration ->
                AndroidRecordingSession(scope, directory, duration, runner)
            } }).parse(listOf("-p", "android", "--record", "--step", "press_home") +
                if (json) listOf("--json") else emptyList())
        val actual = assertNotNull(result)
        val recovery = "retained ${processes.remote}/recording.mp4 on serial-1"
        assertEquals(1, actual.exitCode)
        assertTrue(assertNotNull(actual.stderr).contains(recovery), actual.stderr)
        val stdout = assertNotNull(actual.stdout)
        assertTrue(stdout.contains("Readiness inspection failed"))
        assertTrue(stdout.contains(recovery))
        if (json) {
            val report = Json.parseToJsonElement(stdout).jsonObject
            assertEquals("Readiness inspection failed",
                report.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
            assertEquals("start", report.getValue("recording").jsonObject.getValue("phase").jsonPrimitive.content)
            assertEquals("skipped", report.getValue("steps").jsonArray.single().jsonObject
                .getValue("status").jsonPrimitive.content)
        }
        assertFalse(processes.removed)
        assertTrue(processes.exited.isCompleted)
    }

    @Test
    fun `parent cancellation during startup stays cancellation with bounded cleanup`() = runTest {
        val started = CompletableDeferred<Unit>()
        val fake = FakeRecordingSession(directory)
        var cleanupEnded = false
        val session = object : RecordingSession by fake {
            override suspend fun start(deadline: RequestDeadline) {
                started.complete(Unit)
                awaitCancellation()
            }
            override suspend fun finish(deadline: RequestDeadline): Path {
                assertEquals(30000L, deadline.remainingMillis())
                try { awaitCancellation() } finally { cleanupEnded = true }
            }
        }
        var report: FlowReport? = null
        val job = launch {
            report = FlowExecutor { RequestDeadline(it) { testScheduler.currentTime * 1_000_000 } }.execute(
                Platform.Android, steps(fake.events), observationOptions = observations(fake.events),
                recordingOptions = options(session))
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull(report)
        assertTrue(cleanupEnded)
        assertEquals(30000L, testScheduler.currentTime)
        assertTrue(fake.events.isEmpty())
    }

    private fun steps(events: MutableList<String>) = listOf("original-7", "original-12").map { id ->
        FlowPreparedStep(id, "press_home", FlowStepKind.ACTION) {
            events += "action"
            """{"result":{"success":true}}"""
        }
    }

    private fun observations(events: MutableList<String>) = FlowObservationOptions(
        collector = FlowObservationCollector(Platform.Android, lazy {
            events += "observation"
            error("Observations must not initialize components")
        }), observe = listOf("screenshot"), onFailure = listOf("screenshot"))

    private fun options(session: RecordingSession) = FlowRecordingOptions(directory,
        lazy { FlowRecordingFactory { _, _, _, _ -> session } })

    private fun <T> cases(values: List<T>, test: suspend TestScope.(T) -> Unit) = values.map { value ->
        DynamicTest.dynamicTest(value.toString()) { runTest { test(value) } }
    }

    private fun assertRejected(report: FlowReport, message: String, exitCode: Int = 1, cleanupMessage: String? = null) {
        assertFalse(report.success)
        assertEquals(exitCode, report.exitCode)
        assertEquals(message, report.error?.message)
        assertEquals(listOf("original-7", "original-12"), report.skippedSteps)
        assertTrue(report.steps.all { it.status == "skipped" && it.certainty == "not_executed" })
        assertNull(report.failedStep)
        assertEquals("skipped", report.observations.single().status)
        assertEquals("skipped", report.evidence.single().status)
        assertEquals("failed", report.recording?.status)
        assertEquals("start", report.recording?.phase)
        val recordingError = if (cleanupMessage == null) report.error else report.error?.copy(
            message = "$message\nRecording finalization failed. $cleanupMessage")
        assertEquals(recordingError, report.recording?.error)
        assertNull(report.recording?.path)
    }
}
