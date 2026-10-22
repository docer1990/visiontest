package com.example.visiontest.cli

import com.example.visiontest.common.RequestDeadline
import com.example.visiontest.recording.RecordingSession
import com.example.visiontest.recording.AndroidRecordingSession
import com.example.visiontest.recording.IosRecordingSession
import com.example.visiontest.recording.FakeAndroidRecordingProcesses
import com.example.visiontest.recording.FakeIosRecordingProcesses
import com.example.visiontest.recording.RecordingProcessRunner
import com.example.visiontest.recording.RecordingCommandResult
import com.example.visiontest.recording.RecordingContainerInspector
import com.example.visiontest.recording.RecordingException
import com.example.visiontest.recording.JvmRecordingProcessRunner
import com.example.visiontest.recording.OwnedRecordingProcess
import com.example.visiontest.recording.movieWithMetadataBeforeVideo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class FlowRecordingCancellationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `cancellation reaps the owned host recorder without signalling an unrelated process`() = runBlocking {
        val source = Files.write(directory.resolve("source.mov"), movieWithMetadataBeforeVideo())
        val artifacts = directory.resolve("artifacts")
        val unrelated = ProcessBuilder("/bin/sleep", "60").start()
        var recorder: Process? = null
        val native = FakeIosRecordingProcesses(artifacts)
        val runner = object : RecordingProcessRunner by native {
            override suspend fun start(
                scope: CoroutineScope,
                command: List<String>,
                deadline: RequestDeadline,
                stderrReadyLine: String?,
            ): OwnedRecordingProcess = JvmRecordingProcessRunner {
                ProcessBuilder("/bin/sh", "-c", """
                    trap 'cp "${'$'}1" "${'$'}2"; exit 0' INT
                    echo 'Recording started' >&2
                    while :; do :; done
                """.trimIndent(), "recorder", source.toString(), command.last()).start().also { recorder = it }
            }.start(scope, command, deadline, stderrReadyLine)
        }
        try {
            withTimeout(10000) {
                val entered = CompletableDeferred<Unit>()
                val hooks = TestRecordingShutdown()
                val caller = launch {
                    FlowExecutor().execute(Platform.Ios,
                        listOf(FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                            entered.complete(Unit)
                            awaitCancellation()
                        }), recordingOptions = FlowRecordingOptions(artifacts,
                            lazy { FlowRecordingFactory { scope, _, path, _ ->
                                IosRecordingSession(scope, path, runner)
                            } }, hooks::register))
                }
                entered.await()
                caller.cancel()
                caller.join()
                assertFalse(checkNotNull(recorder).isAlive)
                assertTrue(unrelated.isAlive)
                assertEquals(1, hooks.removed)
                RecordingContainerInspector().validateVideo(artifacts.resolve("recording.mov"), RequestDeadline(1000))
            }
        } finally {
            recorder?.destroyForcibly()?.waitFor(5, TimeUnit.SECONDS)
            unrelated.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `shutdown does not renew a finalization budget already in progress`() = runTest {
        val hooks = TestRecordingShutdown()
        val fake = FakeRecordingSession(directory)
        val entered = CompletableDeferred<Unit>()
        fake.beforeFinish = { entered.complete(Unit); awaitCancellation() }
        val caller = launch {
            FlowExecutor { ms -> RequestDeadline(ms) { testScheduler.currentTime * 1_000_000 } }.execute(
                Platform.Android, emptyList(), recordingOptions = FlowRecordingOptions(directory,
                    lazy { FlowRecordingFactory { _, _, _, _ -> fake } }, hooks::register))
        }
        entered.await()
        advanceTimeBy(10000)
        hooks.callbacks.single()()
        caller.join()
        assertEquals(30000, testScheduler.currentTime)
        assertEquals(1, fake.events.count { it == "record-stop" })
        assertTrue(caller.isCancelled)
        assertEquals(1, hooks.removed)
        assertTrue(hooks.callbacks.isEmpty())
    }

    @Test
    fun `cancelling a native exit observer is not a premature recorder exit`() = runBlocking {
        for (platform in Platform.entries) {
            val artifacts = directory.resolve(platform.value)
            val ready = CompletableDeferred<Unit>()
            lateinit var session: RecordingSession
            val caller = launch {
                session = when (platform) {
                    Platform.Android -> AndroidRecordingSession(this, artifacts, 150000,
                        FakeAndroidRecordingProcesses())
                    Platform.Ios -> IosRecordingSession(this, artifacts,
                        FakeIosRecordingProcesses(artifacts).also { it.firstFrame.complete(Unit) })
                }
                try {
                    session.start(RequestDeadline(1000))
                    ready.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        yield()
                        session.finish(RequestDeadline(1000))
                    }
                }
            }
            ready.await()
            caller.cancel()
            caller.join()
            assertFalse(session.terminal.isCompleted, platform.value)
        }
    }

    @Test
    fun `stop transfer validation and cleanup share one total deadline`() = runTest {
        val native = FakeAndroidRecordingProcesses().also { it.signalDelay = 15000 }
        var transferCancelled = false
        val runner = object : RecordingProcessRunner by native {
            override suspend fun run(command: List<String>, deadline: RequestDeadline, stdoutLimitBytes: Int)
                : RecordingCommandResult {
                if ("pull" in command) {
                    try { delay(16000) } catch (error: CancellationException) {
                        transferCancelled = true
                        throw error
                    }
                }
                return native.run(command, deadline, stdoutLimitBytes)
            }
        }
        val session = AndroidRecordingSession(backgroundScope, directory, 150000, runner)
        session.start(RequestDeadline(1000))
        val failure = assertFailsWith<RecordingException> {
            session.finish(RequestDeadline(30000) { testScheduler.currentTime * 1_000_000 })
        }
        assertTrue(transferCancelled)
        assertTrue(testScheduler.currentTime <= 30000)
        assertTrue(Files.list(directory).use { it.count() == 0L })
        assertFalse(native.removed)
        assertTrue(failure.message.orEmpty().contains(native.remote))
        assertTrue(native.exited.isCompleted)
    }

    @Test
    fun `native discovery readiness and transfer cancellation release only owned resources`() = runBlocking {
        withTimeout(10000) {
            for (platform in Platform.entries) {
                val phases = if (platform == Platform.Android) listOf("discovery", "readiness", "transfer")
                    else listOf("discovery", "readiness")
                for (phase in phases) verifyNativeCancellation(platform, phase)
            }
        }
    }

    private suspend fun verifyNativeCancellation(platform: Platform, phase: String) = coroutineScope {
        val artifacts = directory.resolve("${platform.value}-$phase")
        val android = FakeAndroidRecordingProcesses()
        val ios = FakeIosRecordingProcesses(artifacts)
        if (phase != "readiness") ios.firstFrame.complete(Unit)
        if (phase == "readiness") android.readGate = CompletableDeferred()
        val native = if (platform == Platform.Android) android else ios
        val entered = CompletableDeferred<Unit>()
        var cancelledDiscovery = false
        val runner = object : RecordingProcessRunner by native {
            override suspend fun run(
                command: List<String>,
                deadline: RequestDeadline,
                stdoutLimitBytes: Int,
            ): RecordingCommandResult {
                if (phase == "discovery") {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelledDiscovery = true }
                }
                if (phase == "transfer" && "pull" in command) {
                    entered.complete(Unit)
                    delay(200)
                }
                return native.run(command, deadline, stdoutLimitBytes)
            }
        }
        val hooks = TestRecordingShutdown()
        var returned = false
        val caller = launch {
            FlowExecutor().execute(platform, emptyList(), recordingOptions = FlowRecordingOptions(artifacts,
                lazy { FlowRecordingFactory { scope, _, path, duration ->
                    when (platform) {
                        Platform.Android -> AndroidRecordingSession(scope, path, duration, runner)
                        Platform.Ios -> IosRecordingSession(scope, path, runner)
                    }
                } }, hooks::register))
            returned = true
        }
        if (phase == "readiness") {
            if (platform == Platform.Android) android.readStarted.await() else ios.launched.await()
        } else entered.await()
        caller.cancel(CancellationException("cancel $phase"))
        caller.join()
        assertTrue(caller.isCancelled)
        assertFalse(returned)
        assertEquals(1, hooks.removed)
        assertTrue(hooks.callbacks.isEmpty())
        if (phase == "discovery") {
            assertTrue(cancelledDiscovery)
            assertFalse(Files.exists(artifacts))
        } else {
            assertNativeFinalized(platform, artifacts, android, ios)
        }
    }

    private suspend fun assertNativeFinalized(
        platform: Platform,
        artifacts: Path,
        android: FakeAndroidRecordingProcesses,
        ios: FakeIosRecordingProcesses,
    ) {
        val name = if (platform == Platform.Android) "recording.mp4" else "recording.mov"
        RecordingContainerInspector().validateVideo(artifacts.resolve(name), RequestDeadline(1000))
        assertEquals(listOf(name), Files.list(artifacts).use { files -> files.map { it.fileName.toString() }.toList() })
        if (platform == Platform.Android) {
            assertTrue(android.exited.isCompleted)
            assertFalse(android.live)
            assertEquals(1, android.signals)
        } else {
            assertTrue(ios.exited.isCompleted)
            assertEquals(1, ios.interrupts)
        }
    }

    @Test
    fun `recording workers survive caller cancellation until finalization completes`() = runTest {
        val fake = FakeRecordingSession(directory)
        lateinit var worker: Job
        fake.beforeFinish = { assertTrue(worker.isActive); delay(200) }
        val entered = CompletableDeferred<Unit>()
        val caller = launch {
            FlowExecutor().execute(Platform.Ios,
                listOf(FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                    entered.complete(Unit)
                    awaitCancellation()
                }), recordingOptions = FlowRecordingOptions(directory, lazy {
                    FlowRecordingFactory { scope, _, _, _ ->
                        worker = scope.launch { awaitCancellation() }
                        fake
                    }
                }))
        }
        entered.await()
        caller.cancel()
        caller.join()
        assertTrue(Files.isRegularFile(directory.resolve("recording.mp4")))
        assertTrue(worker.isCompleted)
    }

    @Test
    fun `shutdown cancels startup or action and shares normal finalization exactly once`() = runTest {
        for (phase in listOf("startup", "action", "finish")) {
            val fake = FakeRecordingSession(directory.resolve(phase))
            val entered = CompletableDeferred<Unit>()
            val hooks = TestRecordingShutdown()
            val session = object : RecordingSession by fake {
                override suspend fun start(deadline: RequestDeadline) {
                    assertEquals(1, hooks.callbacks.size)
                    fake.start(deadline)
                    if (phase == "startup") { entered.complete(Unit); awaitCancellation() }
                }
            }
            fake.beforeFinish = {
                if (phase == "finish") entered.complete(Unit)
                delay(200)
            }
            val caller = launch {
                FlowExecutor { ms -> RequestDeadline(ms) { testScheduler.currentTime * 1_000_000 } }
                    .execute(Platform.Android, listOf(FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                        if (phase == "action") { entered.complete(Unit); awaitCancellation() }
                        """{"result":{"success":true}}"""
                    }), recordingOptions = FlowRecordingOptions(directory,
                        lazy { FlowRecordingFactory { _, _, _, _ -> session } }, hooks::register))
            }
            runCurrent()
            assertEquals(1, hooks.callbacks.size)
            entered.await()
            val shutdown = hooks.callbacks.single()
            shutdown()
            caller.join()
            shutdown()
            assertTrue(caller.isCancelled)
            assertEquals(1, fake.events.count { it == "record-stop" })
            assertTrue(hooks.callbacks.isEmpty())
            assertEquals(1, hooks.removed)
        }
    }

    @Test
    fun `normal completion unregisters shutdown without touching unrelated work`() = runTest {
        val hooks = TestRecordingShutdown()
        val unrelated = backgroundScope.launch { awaitCancellation() }
        val fake = FakeRecordingSession(directory)
        val report = FlowExecutor().execute(Platform.Android, emptyList(), recordingOptions =
            FlowRecordingOptions(directory, lazy { FlowRecordingFactory { _, _, _, _ -> fake } }, hooks::register))
        assertTrue(report.success)
        assertEquals(1, hooks.removed)
        assertTrue(hooks.callbacks.isEmpty())
        assertTrue(unrelated.isActive)
    }

    @Test
    fun `caller cancellation propagates after cleanup at every flow phase`() = runTest {
        val phases = listOf("startup", "action", "observation", "evidence", "finish")
        for (platform in Platform.entries) for (phase in phases) {
            val artifacts = directory.resolve("${platform.value}-$phase")
            val fake = FakeRecordingSession(artifacts).also { it.platform = platform }
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<CancellationException>()
            suspend fun pause() { entered.complete(Unit); awaitCancellation() }
            val session = object : RecordingSession by fake {
                override suspend fun start(deadline: RequestDeadline) {
                    fake.start(deadline)
                    if (phase == "startup") pause()
                }
            }
            fake.beforeFinish = {
                if (phase == "finish") entered.complete(Unit)
                delay(200)
            }
            val components = mockk<ComponentHolder>(relaxed = true)
            coEvery { components.androidAutomationRegistrar.getUiHierarchy() } coAnswers { pause(); error("cancelled") }
            coEvery { components.iosAutomationRegistrar.getUiHierarchy(null) } coAnswers { pause(); error("cancelled") }
            val steps = listOf(FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                if (phase == "action") pause()
                if (phase == "evidence") """{"result":{"success":false,"error":"blocked"}}"""
                else """{"result":{"success":true}}"""
            })
            var returned = false
            val caller = launch {
                try {
                    FlowExecutor { ms -> RequestDeadline(ms) { testScheduler.currentTime * 1_000_000 } }
                        .execute(platform, steps, observationOptions = FlowObservationOptions(
                            collector = FlowObservationCollector(platform, lazy { components }, artifacts = artifacts),
                            observe = if (phase == "observation") listOf("hierarchy") else emptyList(),
                            onFailure = if (phase == "evidence") listOf("hierarchy") else emptyList()),
                            recordingOptions = FlowRecordingOptions(artifacts,
                                lazy { FlowRecordingFactory { _, _, _, _ -> session } }))
                    returned = true
                } catch (error: CancellationException) {
                    cancelled.complete(error)
                    throw error
                }
            }
            entered.await()
            val cause = CancellationException("caller cancelled $phase")
            caller.cancel(cause)
            caller.join()
            assertEquals(cause.message, cancelled.await().message)
            assertFalse(returned)
            assertEquals(1, fake.events.count { it == "record-stop" }, "$platform $phase")
            val extension = if (platform == Platform.Ios) "mov" else "mp4"
            assertTrue(Files.isRegularFile(artifacts.resolve("recording.$extension")), "$platform $phase")
        }
    }

    @Test
    fun `never completing finalization has one thirty second budget after cancellation`() = runTest {
        val fake = FakeRecordingSession(directory)
        fake.beforeFinish = { awaitCancellation() }
        val entered = CompletableDeferred<Unit>()
        val caller = launch {
            FlowExecutor { ms -> RequestDeadline(ms) { testScheduler.currentTime * 1_000_000 } }.execute(
                Platform.Android, listOf(FlowPreparedStep("step-1", "press_home", FlowStepKind.ACTION) {
                    entered.complete(Unit)
                    awaitCancellation()
                }), recordingOptions = FlowRecordingOptions(directory,
                    lazy { FlowRecordingFactory { _, _, _, _ -> fake } }))
        }
        entered.await()
        caller.cancel()
        caller.join()
        assertEquals(30000, testScheduler.currentTime)
        assertEquals(1, fake.events.count { it == "record-stop" })
    }
}

private class TestRecordingShutdown {
    val callbacks = mutableListOf<suspend () -> Unit>()
    var removed = 0
    fun register(callback: suspend () -> Unit): AutoCloseable {
        callbacks += callback
        return AutoCloseable { callbacks.remove(callback); removed++ }
    }
}
