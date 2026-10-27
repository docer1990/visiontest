package dev.visiontest.performance

import dev.visiontest.cli.CliExecutionContext
import dev.visiontest.cli.CliRequestExecutor
import dev.visiontest.cli.ComponentHolder
import dev.visiontest.cli.Platform
import dev.visiontest.ios.ProcessExecutor
import dev.visiontest.common.RequestLogger
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestTraceTest {
    @TempDir lateinit var root: Path

    @Test
    fun `trace finalization diagnostic follows operation error and ignores log destination`() = runBlocking {
        val holder = mockk<ComponentHolder>(relaxed = true)
        coEvery { holder.isServerRunning(Platform.Android) } returns true
        coEvery { holder.androidAutomationRegistrar.pressHome() } coAnswers {
            RequestLogger.getLogger("operation").warn("operation warning")
            error("operation failed")
        }
        var pending: Job? = null
        try {
            val result = CliRequestExecutor().execute(
                arrayOf("--trace-performance", "trace.jsonl", "press_home", "--platform", "android"),
                CliExecutionContext(workingDirectory = root,
                    loggingProperties = mapOf("org.slf4j.simpleLogger.logFile" to "request.log")),
                acquireComponents = { trace ->
                    pending = launch(start = CoroutineStart.UNDISPATCHED) {
                        trace.recorder.invocation("press_home", "android") { awaitCancellation() }
                    }
                    holder
                })
            assertEquals(1, result.exitCode)
            assertEquals("operation failed\nVisionTest performance trace is unavailable or incomplete.", result.stderr)
            assertEquals("WARN operation - operation warning\n", Files.readString(root.resolve("request.log")))
        } finally {
            pending?.cancelAndJoin()
        }
    }

    @Test
    fun `unavailable trace diagnostic precedes operation error`() {
        val holder = mockk<ComponentHolder>(relaxed = true)
        coEvery { holder.isServerRunning(Platform.Android) } returns true
        coEvery { holder.androidAutomationRegistrar.pressHome() } throws IllegalStateException("operation failed")
        val result = CliRequestExecutor().execute(
            arrayOf("--trace-performance", root.toString(), "press_home", "--platform", "android"),
            CliExecutionContext(workingDirectory = root), { holder })
        assertEquals(1, result.exitCode)
        assertEquals("VisionTest performance trace is unavailable or incomplete.\noperation failed", result.stderr)
    }

    @Test
    fun `retained contextual recorder routes spans and metrics to successive sessions`() = runBlocking {
        val retained = TraceRecorder.Contextual
        val sessions = List(2) { mutableListOf<TraceEvent>() }
        for (events in sessions) {
            val recorder = TraceRecorder(emit = events::add)
            recorder.invocation("press_home", "android") {
                withContext(Dispatchers.IO) {
                    retained.span(TraceStage.OPERATION) {
                        retained.metric(TraceMetric.POLL_COUNT, 2)
                        retained.operationOutcome(OperationOutcome.SUCCESS)
                        assertTrue(retained.nativeRequest() != null)
                        retained.interval(TraceStage.POLL_WAIT, 0, 1)
                    }
                }
            }
            assertTrue(recorder.seal().complete)
            val operation = events.single { it.stage == TraceStage.OPERATION }
            assertEquals(2, operation.metrics[TraceMetric.POLL_COUNT])
            assertEquals(OperationOutcome.SUCCESS, operation.operationOutcome)
            assertEquals(3, events.size)
            assertEquals(1, events.map { it.sessionId }.distinct().size)
        }
        assertNotEquals(sessions[0].first().sessionId, sessions[1].first().sessionId)
        retained.span(TraceStage.OPERATION) { assertNull(retained.nativeRequest()) }
        TraceRecorder.Disabled.invocation("press_home", "android") {
            retained.span(TraceStage.OPERATION) { assertNull(retained.nativeRequest()) }
        }
        assertEquals(listOf(3, 3), sessions.map { it.size })
    }

    @Test
    fun `cached graph captures later request spans and emits initialization only at acquisition`() {
        val holder = mockk<ComponentHolder>(relaxed = true)
        val retainedProcess = ProcessExecutor(trace = TraceRecorder.Contextual)
        coEvery { holder.isServerRunning(Platform.Android) } returns true
        coEvery { holder.androidAutomationRegistrar.pressHome() } coAnswers {
            retainedProcess.execute("/bin/echo", "done").output
        }
        var cached: ComponentHolder? = null
        val acquire: (TraceRuntime) -> ComponentHolder = { trace ->
            cached ?: trace.initializeComponents { holder }.also { cached = it }
        }
        for (index in 0..1) {
            val result = CliRequestExecutor().execute(arrayOf("--trace-performance", "$index.jsonl",
                "press_home", "--platform", "android"), CliExecutionContext(workingDirectory = root),
                acquireComponents = acquire)
            assertEquals(0, result.exitCode, result.stderr)
            val lines = Files.readAllLines(root.resolve("$index.jsonl"))
            assertEquals(1, lines.count { "\"stage\":\"simctl\"" in it })
            assertEquals(if (index == 0) 1 else 0, lines.count { "\"stage\":\"component.init\"" in it })
            assertTrue(lines.last().contains("\"complete\":true"))
        }
    }
}
