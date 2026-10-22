package com.example.visiontest.recording

import com.example.visiontest.common.RequestDeadline
import com.example.visiontest.NoSimulatorAvailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class IosRecordingSessionTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `recording target is a writable vacant temporary MOV path`() = runTest {
        val destination = directory.resolve("artifacts")
        val runner = FakeIosRecordingProcesses(destination)
        runner.rejectExistingOutput = true
        runner.firstFrame.complete(Unit)
        val session = IosRecordingSession(backgroundScope, destination, runner)
        assertFalse(Files.exists(destination))
        assertTrue(runner.commands.isEmpty())
        session.start(RequestDeadline(1000))
        assertEquals(destination.resolve("recording.mov"), session.finish(RequestDeadline(1000)))
    }

    @Test
    fun `finishing before the monitor resumes preserves premature exit`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        runner.firstFrame.complete(Unit)
        val session = IosRecordingSession(backgroundScope, directory, runner)
        session.start(RequestDeadline(1000))
        Files.write(runner.temporary, runner.finalBytes)
        runner.exited.complete(RecordingCommandResult(9, byteArrayOf(), "early exit"))
        assertEquals(directory.resolve("recording.mov"), session.finish(RequestDeadline(1000)))
        assertEquals(RecordingExit(9, "early exit"), withTimeoutOrNull(100) { session.terminal.await() })
        assertEquals(0, runner.interrupts)
    }

    @Test
    fun `exit after the finalization snapshot remains premature when its observer is delayed`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        runner.firstFrame.complete(Unit)
        val session = IosRecordingSession(backgroundScope, directory, runner)
        session.start(RequestDeadline(1000))
        runner.afterExitSnapshot = {
            Files.write(runner.temporary, runner.finalBytes)
            runner.exited.complete(RecordingCommandResult(0, byteArrayOf(), "exit before stop intent"))
        }

        val path = session.finish(RequestDeadline(1000))
        runCurrent()

        assertEquals(directory.resolve("recording.mov"), path)
        assertContentEquals(runner.finalBytes, Files.readAllBytes(path))
        assertEquals(0, runner.interrupts)
        assertTrue(session.terminal.isCompleted)
        assertEquals(RecordingExit(0, "exit before stop intent"), session.terminal.await())
        assertEquals(path, session.finish(RequestDeadline(0)))
    }

    @Test
    fun `nonzero intentional stop is a finalization error`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        runner.firstFrame.complete(Unit)
        runner.stopCode = 3
        val session = IosRecordingSession(backgroundScope, directory, runner)
        session.start(RequestDeadline(1000))
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertFalse(Files.exists(directory.resolve("recording.mov")))
        assertFalse(Files.exists(runner.temporary))
    }

    @Test
    fun `startup readiness consumes only its supplied deadline`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        val session = IosRecordingSession(backgroundScope, directory, runner)
        val start = async {
            runCatching { session.start(RequestDeadline(100) { testScheduler.currentTime * 1_000_000 }) }
        }
        runner.launched.await()
        val result = withTimeoutOrNull(200) { start.await() }
        assertIs<RecordingException>(result?.exceptionOrNull())
        assertEquals(100, testScheduler.currentTime)
        session.finish(RequestDeadline(1000))
        assertEquals(1, runner.interrupts)
    }

    @Test
    fun `stop timeout forces only the owned process within the same deadline`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        runner.firstFrame.complete(Unit)
        runner.interruptDelay = 2000
        val session = IosRecordingSession(backgroundScope, directory, runner)
        session.start(RequestDeadline(1000))
        val deadline = RequestDeadline(1000) { testScheduler.currentTime * 1_000_000 }
        assertFailsWith<RecordingException> { session.finish(deadline) }
        assertEquals(1, runner.forcedStops)
        assertTrue(runner.exited.isCompleted)
        assertFalse(Files.exists(runner.temporary))
        assertTrue(testScheduler.currentTime <= 1000)
        runner.stopDeadlines.forEach { assertSame(deadline, it) }
    }

    @Test
    fun `cancelled discovery ends before finish without launching a recorder`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        runner.discoveryGate = CompletableDeferred()
        val session = IosRecordingSession(backgroundScope, directory, runner)
        val start = launch { session.start(RequestDeadline(1000)) }
        runCurrent()
        start.cancelAndJoin()
        assertTrue(runner.discoveryCancelled)
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertEquals(1, runner.commands.size)
        assertEquals(0, runner.interrupts)
    }

    @Test
    fun `cancelled startup can finalize from the cancelled caller`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        val session = IosRecordingSession(backgroundScope, directory, runner)
        val finalized = CompletableDeferred<Path>()
        val start = launch {
            try {
                session.start(RequestDeadline(1000))
            } finally {
                finalized.complete(session.finish(RequestDeadline(1000)))
            }
        }
        runner.launched.await()
        start.cancelAndJoin()
        assertTrue(Files.isRegularFile(finalized.await()))
        assertEquals(1, runner.interrupts)
        assertFalse(Files.exists(runner.temporary))
    }

    @Test
    fun `successful finalization is cached without signalling twice`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        runner.firstFrame.complete(Unit)
        val session = IosRecordingSession(backgroundScope, directory, runner)
        session.start(RequestDeadline(1000))
        val path = session.finish(RequestDeadline(1000))
        assertEquals(path, session.finish(RequestDeadline(0)))
        assertEquals(1, runner.interrupts)
    }

    @Test
    fun `invalid MOV finalization removes only its temporary and caches failure`() = runTest {
        val unrelated = Files.writeString(directory.resolve("unrelated.mov"), "keep")
        val runner = FakeIosRecordingProcesses(directory)
        runner.finalBytes = byteArrayOf(1, 2, 3)
        runner.firstFrame.complete(Unit)
        val session = IosRecordingSession(backgroundScope, directory, runner)
        session.start(RequestDeadline(1000))
        val failure = assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertFalse(Files.exists(runner.temporary))
        assertFalse(Files.exists(directory.resolve("recording.mov")))
        assertEquals("keep", Files.readString(unrelated))
        val repeated = assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertEquals(failure.message, repeated.message)
        assertEquals(1, runner.interrupts)
    }

    @Test
    fun `failed process launch still owns temporary cleanup`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        runner.launchFailure = IOException("launch failed")
        val session = IosRecordingSession(backgroundScope, directory, runner)
        assertFailsWith<RecordingException> { session.start(RequestDeadline(1000)) }
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertFalse(Files.exists(runner.temporary))
    }

    @Test
    fun `filesystem failures are execution errors`() = runTest {
        val file = Files.writeString(directory.resolve("not-a-directory"), "keep")
        val runner = FakeIosRecordingProcesses(directory)
        val session = IosRecordingSession(backgroundScope, file, runner)
        assertFailsWith<RecordingException> { session.start(RequestDeadline(1000)) }
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertEquals("keep", Files.readString(file))
    }

    @Test
    fun `exit before readiness is observed without cancelling siblings`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        val session = IosRecordingSession(backgroundScope, directory, runner)
        val start = async { runCatching { session.start(RequestDeadline(1000)) } }
        runner.launched.await()
        runner.exited.complete(RecordingCommandResult(7, byteArrayOf(), "recorder failed"))
        assertIs<RecordingException>(withTimeoutOrNull(100) { start.await() }?.exceptionOrNull())
        assertEquals(RecordingExit(7, "recorder failed"), session.terminal.await())
    }

    @Test
    fun `readiness followed immediately by exit does not start a flow`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        val session = IosRecordingSession(backgroundScope, directory, runner)
        val start = async { runCatching { session.start(RequestDeadline(1000)) } }
        runner.launched.await()
        runner.firstFrame.complete(Unit)
        runner.exited.complete(RecordingCommandResult(0, byteArrayOf(), "ended"))
        assertIs<RecordingException>(start.await().exceptionOrNull())
        assertEquals(0, session.terminal.await().exitCode)
    }

    @Test
    fun `premature zero exit after startup is a terminal value`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        runner.firstFrame.complete(Unit)
        val session = IosRecordingSession(backgroundScope, directory, runner)
        session.start(RequestDeadline(1000))
        runner.exited.complete(RecordingCommandResult(0, byteArrayOf(), "ended early"))
        assertEquals(RecordingExit(0, "ended early"), withTimeoutOrNull(100) { session.terminal.await() })
    }

    @Test
    fun `exit observation failure is a terminal value rather than sibling cancellation`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        runner.firstFrame.complete(Unit)
        val session = IosRecordingSession(backgroundScope, directory, runner)
        session.start(RequestDeadline(1000))
        runner.exited.completeExceptionally(IOException("broken pipe"))
        assertEquals(RecordingExit(null, "broken pipe"), withTimeoutOrNull(100) { session.terminal.await() })
    }

    @Test
    fun `missing xcrun is an execution error`() = verifyDiscoveryFailure {
        it.discoveryFailure = IOException("Cannot run program xcrun")
    }

    @Test
    fun `malformed discovery is an execution error`() = verifyDiscoveryFailure { it.discoveryJson = "not json" }

    @Test
    fun `oversized discovery is bounded`() = verifyDiscoveryFailure { it.discoveryJson = "x".repeat(1_048_577) }

    @Test
    fun `nonzero discovery exit is an execution error`() = verifyDiscoveryFailure { it.discoveryCode = 1 }

    @Test
    fun `no simulator retains its exit four exception`() = verifyDiscoveryFailure(missingSimulator = true) {
        it.discoveryJson = """{"devices":{}}"""
    }

    @Test
    fun `shutdown selection fails without booting or switching`() = verifyDiscoveryFailure {
        it.discoveryJson = it.discoveryJson.replace("Booted", "Shutdown")
    }

    @Test
    fun `blank UDID fails before launching a recorder`() = verifyDiscoveryFailure {
        it.discoveryJson = it.discoveryJson.replace("simulator-1", " ")
    }

    private fun verifyDiscoveryFailure(
        missingSimulator: Boolean = false,
        configure: (FakeIosRecordingProcesses) -> Unit,
    ) = runTest {
        val runner = FakeIosRecordingProcesses(directory).also(configure)
        runner.firstFrame.complete(Unit)
        val session = IosRecordingSession(backgroundScope, directory, runner)
        val failure = assertFailsWith<Exception> { session.start(RequestDeadline(1000)) }
        if (missingSimulator) assertIs<NoSimulatorAvailableException>(failure)
        else assertIs<RecordingException>(failure)
        assertEquals(1, runner.commands.size)
        assertEquals(1_048_576, runner.discoveryLimit)
    }

    @Test
    fun `the session waits for the first frame before returning`() = runTest {
        val runner = FakeIosRecordingProcesses(directory)
        val session = IosRecordingSession(backgroundScope, directory, runner)
        val start = async { session.start(RequestDeadline(1000)) }
        runner.launched.await()
        runCurrent()
        assertFalse(start.isCompleted)
        runner.firstFrame.complete(Unit)
        start.await()
        assertEquals("simulator-1", runner.recordedTarget)
        assertEquals(listOf("xcrun", "simctl", "list", "devices", "available", "--json"), runner.commands[0])
        assertEquals(listOf("xcrun", "simctl", "io", "simulator-1", "recordVideo", "--codec=h264"),
            runner.commands[1].dropLast(1))
        assertEquals("Recording started", runner.readyLine)
        val path = session.finish(RequestDeadline(1000))
        assertEquals(directory.resolve("recording.mov"), path)
        assertTrue(Files.isRegularFile(path))
        assertEquals(1, runner.interrupts)
        runCurrent()
        assertFalse(session.terminal.isCompleted)
        assertFalse(Files.exists(runner.temporary))
    }
}

@OptIn(kotlinx.coroutines.InternalForInheritanceCoroutinesApi::class)
private class FakeIosRecordingProcesses(private val directory: Path) : RecordingProcessRunner {
    val firstFrame = CompletableDeferred<Unit>()
    var recordedTarget: String? = null
    val commands = mutableListOf<List<String>>()
    var readyLine: String? = null
    var temporary: Path? = null
    var interrupts = 0
    var forcedStops = 0
    var interruptDelay = 0L
    var stopCode = 0
    var rejectExistingOutput = false
    val stopDeadlines = mutableListOf<RequestDeadline>()
    val exited = CompletableDeferred<RecordingCommandResult>()
    var afterExitSnapshot: (() -> Unit)? = null
    val launched = CompletableDeferred<Unit>()
    var launchFailure: Exception? = null
    var finalBytes = movieFileType() + movieBox("mdat", byteArrayOf(1, 2, 3, 4)) +
        movieBox("moov", movieTrack(options = MovieTrackOptions(dataReference = movieDataReference("alis"))))
    var discoveryFailure: Exception? = null
    var discoveryGate: CompletableDeferred<Unit>? = null
    var discoveryCancelled = false
    var discoveryCode = 0
    var discoveryLimit: Int? = null
    var discoveryJson = """{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-27-0":[
        {"udid":"shutdown-first","name":"iPhone 16","state":"Shutdown",
        "isAvailable":true,"deviceTypeIdentifier":"com.apple.CoreSimulator.SimDeviceType.iPhone-16"},
        {"udid":"simulator-1","name":"iPhone 17","state":"Booted",
        "isAvailable":true,"deviceTypeIdentifier":"com.apple.CoreSimulator.SimDeviceType.iPhone-17"}
    ]}}"""

    override suspend fun run(
        command: List<String>,
        deadline: RequestDeadline,
        stdoutLimitBytes: Int,
    ): RecordingCommandResult {
        commands += command
        discoveryLimit = stdoutLimitBytes
        try {
            discoveryGate?.await()
        } catch (cancelled: CancellationException) {
            discoveryCancelled = true
            throw cancelled
        }
        discoveryFailure?.let { throw it }
        if (discoveryJson.toByteArray().size > stdoutLimitBytes) throw RecordingException("Discovery output too large")
        return RecordingCommandResult(discoveryCode, discoveryJson.toByteArray(), "discovery diagnostic")
    }

    override suspend fun start(
        scope: CoroutineScope,
        command: List<String>,
        deadline: RequestDeadline,
        stderrReadyLine: String?,
    ): OwnedRecordingProcess {
        commands += command
        recordedTarget = command[3]
        readyLine = stderrReadyLine
        temporary = Path.of(command.last())
        assertEquals(directory, temporary?.parent)
        assertTrue(command.last().endsWith(".mov"))
        if (rejectExistingOutput && Files.exists(temporary)) throw IOException("Output already exists")
        launched.complete(Unit)
        launchFailure?.let { throw it }
        return object : OwnedRecordingProcess {
            override val pid = 2_000_000_000L
            override val firstStdoutLine = CompletableDeferred<String>()
            override val stderrReady = firstFrame
            override val exit = object : Deferred<RecordingCommandResult> by exited {
                override val isCompleted: Boolean
                    get() {
                        val snapshot = exited.isCompleted
                        val afterSnapshot = afterExitSnapshot
                        afterExitSnapshot = null
                        afterSnapshot?.invoke()
                        return snapshot
                    }
            }
            override fun isAlive() = !exited.isCompleted
            override suspend fun interrupt(deadline: RequestDeadline) {
                interrupts++
                if (exited.isCompleted) return
                stopDeadlines += deadline
                delay(interruptDelay)
                Files.write(temporary, finalBytes)
                exited.complete(RecordingCommandResult(stopCode, byteArrayOf(), "stop diagnostic"))
            }
            override suspend fun forceStop(deadline: RequestDeadline) {
                forcedStops++
                stopDeadlines += deadline
                exited.complete(RecordingCommandResult(137, byteArrayOf(), "forced"))
            }
        }
    }
}
