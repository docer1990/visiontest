package com.example.visiontest.cli

import com.example.visiontest.android.AutomationClient
import com.example.visiontest.common.RequestDeadline
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.recording.RecordingExit
import com.example.visiontest.recording.RecordingSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlowRecordingInterruptionTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `zero and nonzero premature exits cancel pending actions on both platforms`() {
        for (platform in Platform.entries) for (exit in listOf(0, 7)) {
            val artifacts = directory.resolve("${platform.value}-$exit")
            val session = FakeRecordingSession(artifacts).also { it.platform = platform }
            val attempt = interruptRecordingRequest(platform, session, artifacts, exit)
            val report = attempt.report
            assertFalse(report.success)
            assertEquals(1, report.exitCode)
            assertEquals("uncertain", report.steps[0].status)
            assertEquals("unknown", report.steps[0].certainty)
            assertEquals("skipped", report.steps[1].status)
            assertEquals("failed", report.recording?.status)
            assertEquals("record", report.recording?.phase)
            assertEquals(report.error, report.recording?.error)
            assertTrue(Files.exists(Path.of(assertNotNull(report.recording?.path))))
            assertEquals(1, attempt.posts)
            assertEquals(0, attempt.laterCalls)
        }
    }

    @Test
    fun `exit before the first operation skips every step`() = runBlocking {
        val fake = FakeRecordingSession(directory)
        val session = object : RecordingSession by fake {
            override suspend fun start(deadline: RequestDeadline) {
                fake.start(deadline)
                fake.terminal.complete(RecordingExit(0, "stopped after readiness"))
            }
        }
        var calls = 0
        val report = FlowExecutor().execute(Platform.Android, List(2) { index ->
            FlowPreparedStep("step-$index", "press_home", FlowStepKind.ACTION) {
                calls++
                """{"result":{"success":true}}"""
            }
        }, recordingOptions = options(session))
        assertEquals(0, calls)
        assertEquals(listOf("skipped", "skipped"), report.steps.map { it.status })
        assertEquals(listOf("not_executed", "not_executed"), report.steps.map { it.certainty })
        assertEquals("record", report.recording?.phase)
        assertEquals(1, report.exitCode)
    }

    @Test
    fun `exit during preparation before action dispatch has a confirmed failure`() = runBlocking {
        val session = FakeRecordingSession(directory)
        val preparing = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        val execution = async {
            FlowExecutor().execute(Platform.Android, listOf(
                FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                    preparing.complete(Unit)
                    response.await()
                }), recordingOptions = options(session))
        }
        preparing.await()
        session.terminal.complete(RecordingExit(7, "before dispatch"))
        val report = withTimeout(5000) { execution.await() }
        assertEquals("failed", report.steps.single().status)
        assertEquals("confirmed", report.steps.single().certainty)
        assertEquals("record", report.recording?.phase)
    }

    @Test
    fun `exit between steps preserves the committed step and skips later dispatch`() = runBlocking {
        val session = FakeRecordingSession(directory)
        var created = 0
        var calls = 0
        val executor = FlowExecutor { budget ->
            created++
            if (created == 4) session.terminal.complete(RecordingExit(0, "between steps"))
            RequestDeadline(budget)
        }
        val report = executor.execute(Platform.Android, List(3) { index ->
            FlowPreparedStep("step-$index", "press_home", FlowStepKind.ACTION) {
                calls++
                """{"result":{"success":true}}"""
            }
        }, recordingOptions = options(session))
        assertEquals(1, calls)
        assertEquals(listOf("succeeded", "skipped", "skipped"), report.steps.map { it.status })
        assertEquals("record", report.recording?.phase)
        assertEquals(1, report.exitCode)
    }

    @Test
    fun `validated success and failure survive cancellation at the result handoff`() {
        for (platform in Platform.entries) for (success in listOf(true, false)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"result":{"success":$success,"error":"blocked"}}"""))
                server.start()
                val android = AutomationClient(server.hostName, server.port)
                val ios = IOSAutomationClient(server.hostName, server.port)
                val dispatcher = RecordingGateDispatcher()
                val session = FakeRecordingSession(directory)
                val evaluate = CompletableDeferred<Unit>()
                val handoff = CompletableDeferred<Unit>()
                var responseReceived = false
                var handoffWaiting = false
                var laterCalls = 0
                runBlocking {
                    val execution = async(dispatcher) {
                        FlowExecutor().execute(platform, listOf(
                            FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                                val raw = when (platform) {
                                    Platform.Android -> android.sendRequest("device.pressHome")
                                    Platform.Ios -> ios.sendRequest("device.pressHome")
                                }
                                responseReceived = true
                                evaluate.await()
                                CoroutineScope(currentCoroutineContext()).launch {
                                    handoffWaiting = true
                                    handoff.await()
                                }
                                raw
                            },
                            FlowPreparedStep("step-2", "press_home", FlowStepKind.ACTION) {
                                laterCalls++
                                """{"result":{"success":true}}"""
                            }), recordingOptions = options(session))
                    }
                    try {
                        dispatcher.runUntil { responseReceived }
                        assertFalse(execution.isCompleted)
                        evaluate.complete(Unit)
                        dispatcher.runUntil { handoffWaiting }
                        assertFalse(execution.isCompleted)
                        session.terminal.complete(RecordingExit(7, "after response validation"))
                        dispatcher.runUntil { execution.isCompleted }
                        assertValidatedOutcome(execution.await(), success)
                        assertEquals(0, laterCalls)
                        assertEquals(1, server.requestCount)
                        assertEquals("POST", assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)).method)
                    } finally {
                        execution.cancel()
                        dispatcher.runUntil { execution.isCompleted }
                    }
                }
            }
        }
    }

    @Test
    fun `exit during final observations cancels the observation without changing completed steps`() {
        for (platform in Platform.entries) FlowObservationFixture(platform).use { fixture ->
            val session = FakeRecordingSession(directory)
            val observing = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            fixture.respond = {
                observing.complete(Unit)
                release.await()
                MockResponse().setBody("""{"result":{"success":true,"pngBase64":"AQID"}}""")
            }
            try {
                runBlocking {
                    val execution = async {
                        FlowExecutor().execute(platform, listOf(
                            FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                                """{"result":{"success":true}}"""
                            }), observationOptions = FlowObservationOptions(
                            collector = FlowObservationCollector(platform, fixture.components, artifacts = directory),
                            observe = listOf("screenshot", "hierarchy")), recordingOptions = options(session))
                    }
                    withTimeout(5000) { observing.await() }
                    session.terminal.complete(RecordingExit(0, "during observation"))
                    val report = withTimeout(5000) { execution.await() }
                    assertEquals("succeeded", report.steps.single().status)
                    assertEquals(listOf("failed", "skipped"), report.observations.map { it.status })
                    assertEquals(report.recording?.error, report.error)
                    assertEquals("record", report.recording?.phase)
                    assertEquals(1, report.exitCode)
                    assertEquals(listOf("ui.screenshot"), fixture.calls)
                }
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `premature exit published during successful finalization retains a failed recording with its video`() =
        runBlocking {
            for (platform in Platform.entries) for (exitCode in listOf(0, 7)) {
                val session = FakeRecordingSession(directory.resolve("${platform.value}-$exitCode"))
                    .also { it.platform = platform }
                val finishing = CompletableDeferred<Unit>()
                val finalize = CompletableDeferred<Unit>()
                session.beforeFinish = {
                    finishing.complete(Unit)
                    finalize.await()
                    session.terminal.complete(RecordingExit(exitCode, "pre-stop exit detected during finalization"))
                }
                var calls = 0
                val execution = async {
                    FlowExecutor().execute(platform, listOf(
                        FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                            calls++
                            """{"result":{"success":true}}"""
                        }), recordingOptions = options(session))
                }
                withTimeout(5000) { finishing.await() }
                assertFalse(session.terminal.isCompleted)
                assertFalse(execution.isCompleted)
                finalize.complete(Unit)
                val report = withTimeout(5000) { execution.await() }
                assertFalse(report.success)
                assertEquals(1, report.exitCode)
                assertEquals("succeeded", report.steps.single().status)
                assertEquals("failed", report.recording?.status)
                assertEquals("record", report.recording?.phase)
                assertEquals(report.error, report.recording?.error)
                assertTrue(assertNotNull(report.error).message.contains("exit $exitCode"))
                assertTrue(report.error.message.contains("pre-stop exit detected during finalization"))
                assertTrue(Files.exists(Path.of(assertNotNull(report.recording?.path))))
                assertEquals(1, calls)
                assertEquals(listOf("record-start", "record-stop"), session.events)
            }
        }

    @Test
    fun `exit after intentional stop is not classified as premature`() = runBlocking {
        val session = FakeRecordingSession(directory)
        val report = FlowExecutor().execute(Platform.Android, listOf(
            FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                """{"result":{"success":true}}"""
            }), recordingOptions = options(session))
        assertTrue(report.success)
        assertEquals("succeeded", report.recording?.status)
        assertEquals(listOf("record-start", "record-stop"), session.events)
        assertFalse(session.terminal.isCompleted)
    }

    @Test
    fun `terminal exit before intentional stop remains a recording failure after the last result`() = runBlocking {
        val session = FakeRecordingSession(directory)
        var created = 0
        val executor = FlowExecutor { budget ->
            created++
            if (created == 4) session.terminal.complete(RecordingExit(0, "before intentional stop"))
            RequestDeadline(budget)
        }
        val report = executor.execute(Platform.Android, listOf(
            FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                """{"result":{"success":true}}"""
            }), recordingOptions = options(session))
        assertFalse(report.success)
        assertEquals("succeeded", report.steps.single().status)
        assertEquals("record", report.recording?.phase)
        assertEquals(1, report.exitCode)
    }

    @Test
    fun `handoff preservation does not hide operation deadlines without recorder failure`() = runTest {
        for (recording in listOf(null, options(FakeRecordingSession(directory)))) {
            val report = FlowExecutor().execute(Platform.Android, listOf(
                FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                    CoroutineScope(currentCoroutineContext()).launch { delay(100) }
                    """{"result":{"success":true}}"""
                }), stepTimeoutMs = 10, recordingOptions = recording)
            assertFalse(report.success)
            assertEquals("TimeoutException", report.error?.type)
            assertEquals("failed", report.steps.single().status)
        }
    }

    private fun options(session: RecordingSession) = FlowRecordingOptions(directory,
        lazy { FlowRecordingFactory { _, _, _, _ -> session } })

    private fun assertValidatedOutcome(report: FlowReport, success: Boolean) {
        assertEquals(if (success) "succeeded" else "failed", report.steps[0].status)
        assertEquals("confirmed", report.steps[0].certainty)
        assertNotNull(report.steps[0].result)
        if (!success) assertEquals("blocked", report.steps[0].error?.message)
        assertEquals("skipped", report.steps[1].status)
        assertEquals("record", report.recording?.phase)
        assertEquals(1, report.exitCode)
    }
}

private class RecordingGateDispatcher : CoroutineDispatcher() {
    private val queued = LinkedBlockingQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queued.add(block)
    }

    fun runUntil(condition: () -> Boolean) {
        while (!condition()) assertNotNull(queued.poll(5, TimeUnit.SECONDS), "Expected coroutine handoff").run()
    }
}

internal data class RecordingInterruptionAttempt(val report: FlowReport, val posts: Int, val laterCalls: Int)

internal fun interruptRecordingRequest(
    platform: Platform,
    session: FakeRecordingSession,
    artifacts: Path,
    exitCode: Int,
): RecordingInterruptionAttempt = MockWebServer().use { server ->
    val received = CompletableDeferred<RecordedRequest>()
    val release = CountDownLatch(1)
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            received.complete(request)
            release.await()
            return MockResponse().setBody("""{"result":{"success":true}}""")
        }
    }
    server.start()
    val android = AutomationClient(server.hostName, server.port)
    val ios = IOSAutomationClient(server.hostName, server.port)
    var laterCalls = 0
    try {
        runBlocking {
            val execution = async {
                FlowExecutor().execute(platform, listOf(
                    FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                        when (platform) {
                            Platform.Android -> android.sendRequest("device.pressHome")
                            Platform.Ios -> ios.sendRequest("device.pressHome")
                        }
                    },
                    FlowPreparedStep("step-2", "press_home", FlowStepKind.ACTION) {
                        laterCalls++
                        """{"result":{"success":true}}"""
                    }), recordingOptions = FlowRecordingOptions(artifacts,
                    lazy { FlowRecordingFactory { _, _, _, _ -> session } }))
            }
            assertEquals("POST", withTimeout(5000) { received.await() }.method)
            session.terminal.complete(RecordingExit(exitCode, "encoder stopped"))
            val report = withTimeoutOrNull(5000) { execution.await() }
            execution.cancel()
            RecordingInterruptionAttempt(assertNotNull(report, "Recorder exit must cancel the pending HTTP request"),
                server.requestCount, laterCalls)
        }
    } finally {
        release.countDown()
    }
}
