package com.example.visiontest.recording

import com.example.visiontest.common.RequestDeadline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidRecordingSessionTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `normal stop waits through process exit until the completion record is published`() = runTest {
        for (zombie in listOf(false, true)) {
            val runner = FakeAndroidRecordingProcesses()
            val artifacts = directory.resolve("video-$zombie")
            val session = AndroidRecordingSession(backgroundScope, artifacts, 150_000, runner)
            session.start(RequestDeadline(1000))
            val fixture = CompletionTransition(directory.resolve("remote-$zombie"), runner, zombie)
            runner.statusFixture = fixture::inspect
            val deadline = RequestDeadline(1000) { testScheduler.currentTime * 1_000_000 }
            val path = session.finish(deadline)
            assertEquals(artifacts.resolve("recording.mp4"), path)
            assertContentEquals(movieWithMetadataBeforeVideo(), Files.readAllBytes(path))
            assertEquals(4, fixture.exitedReads)
            assertEquals(1, runner.signals)
            assertEquals(0, runner.forcedRemoteStops)
            assertTrue(fixture.deadlines.all { it === deadline })
        }
    }

    @Test
    fun `missing completion publication consumes only the supplied finalization deadline`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        val session = AndroidRecordingSession(backgroundScope, directory.resolve("video"), 150_000, runner)
        session.start(RequestDeadline(1000))
        val fixture = CompletionTransition(directory.resolve("remote"), runner, publish = false)
        runner.statusFixture = fixture::inspect
        val deadline = RequestDeadline(1000) { testScheduler.currentTime * 1_000_000 }
        assertFailsWith<RecordingException> { session.finish(deadline) }
        assertTrue(fixture.exitedReads > 1)
        assertTrue(testScheduler.currentTime >= 800)
        assertTrue(testScheduler.currentTime <= 1000)
        assertFalse(runner.commands.any { "pull" in it })
        assertEquals(0, runner.forcedRemoteStops)
    }

    @Test
    fun `a different running command is not accepted as a completion transition`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        val session = AndroidRecordingSession(backgroundScope, directory.resolve("video"), 150_000, runner)
        session.start(RequestDeadline(1000))
        val remote = directory.resolve("remote")
        val fixture = CompletionTransition(remote, runner)
        Files.writeString(remote.resolve("proc/1234/cmdline"), "unrelated-process\u0000")
        runner.statusFixture = fixture::inspect
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertEquals(0, runner.signals)
        assertEquals(0, runner.forcedRemoteStops)
        assertFalse(runner.removed)
        assertFalse(runner.commands.any { "pull" in it })
    }

    @Test
    fun `both wrapper exit paths publish complete status through an atomic rename`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        val session = AndroidRecordingSession(backgroundScope, directory.resolve("video"), 150_000, runner)
        session.start(RequestDeadline(1000))
        val wrapper = unquoteRemoteScript(runner.wrapper).replace(runner.remote, directory.toString())
        val publication = checkNotNull(Regex("publish_completion\\(\\) \\{.*?\n}", RegexOption.DOT_MATCHES_ALL)
            .find(wrapper)) { "The wrapper needs one atomic completion publisher" }.value
        assertEquals(3, Regex("publish_completion").findAll(wrapper).count())
        val script = """
            mv() {
                [ ! -e $directory/done ] || exit 11
                [ "${'$'}(cat $directory/done.tmp)" = 7 ] || exit 12
                command mv "${'$'}@"
            }
            $publication
            publish_completion 7
        """.trimIndent()
        val result = runLocalShell(script)
        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("7\n", Files.readString(directory.resolve("done")))
        assertFalse(Files.exists(directory.resolve("done.tmp")))
        session.finish(RequestDeadline(1000))
    }

    @Test
    fun `cancellation recovery waits for the ownership record to finish publishing`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.notifyPid = false
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        val start = launch { session.start(RequestDeadline(1000)) }
        runner.launched.await()
        start.cancelAndJoin()
        runner.pendingOwnershipReads = 2
        val path = session.finish(RequestDeadline(1000) { testScheduler.currentTime * 1_000_000 })
        assertEquals(directory.resolve("recording.mp4"), path)
        assertEquals(1, runner.signals)
        assertEquals(0, runner.pendingOwnershipReads)
        assertEquals(100, testScheduler.currentTime)
    }

    @Test
    fun `premature exit retains diagnostics merged by the remote PTY`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        session.start(RequestDeadline(1000))
        runner.exited.complete(RecordingCommandResult(7, "${runner.token} 1234\nencoder failed".toByteArray(), ""))
        val exit = withTimeoutOrNull(100) { session.terminal.await() }
        assertEquals(7, exit?.exitCode)
        assertTrue(exit?.diagnostics.orEmpty().contains("encoder failed"))
    }

    @Test
    fun `PATH resolution pins a relative adb launcher as an absolute executable`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.executable = "tools/adb"
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        session.start(RequestDeadline(1000))
        session.finish(RequestDeadline(1000))
        val expected = Path.of("tools/adb").toAbsolutePath().toString()
        assertTrue(runner.commands.drop(1).all { it.first() == expected })
        assertEquals(1, runner.commands.count { it.first() == "/bin/sh" })
    }

    @Test
    fun `unresponsive remote cleanup reserves time to close and reap the transport`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        session.start(RequestDeadline(1000))
        runner.statusDelay = 2000
        val deadline = RequestDeadline(1000) { testScheduler.currentTime * 1_000_000 }
        assertFailsWith<RecordingException> { session.finish(deadline) }
        assertEquals(1, runner.forcedTransportStops)
        assertTrue(runner.exited.isCompleted)
        assertTrue(Files.list(directory).use { it.count() == 0L })
        assertTrue(testScheduler.currentTime <= 1000)
    }

    @Test
    fun `failed capability inspection cannot authorize recording`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.helpCode = 1
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        assertFailsWith<RecordingException> { session.start(RequestDeadline(1000)) }
        assertFalse(runner.launched.isCompleted)
    }

    @Test
    fun `malformed PID notification cannot reach container inspection`() = runTest {
        for (pid in listOf("0", "-1", "1234;kill -9 1", "2147483648", "9999999999999999999999")) {
            val runner = FakeAndroidRecordingProcesses()
            runner.notificationPid = pid
            val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
            assertFailsWith<RecordingException> { session.start(RequestDeadline(1000)) }
            assertTrue(runner.reads.isEmpty())
            session.finish(RequestDeadline(1000))
            Files.delete(directory.resolve("recording.mp4"))
        }
    }

    @Test
    fun `nonzero normal recorder finalization is a failure`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.stopCode = 3
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        session.start(RequestDeadline(1000))
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertFalse(Files.exists(directory.resolve("recording.mp4")))
    }

    @Test
    fun `remote inspections and signals use non PTY shell with remote exit status`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        session.start(RequestDeadline(1000))
        session.finish(RequestDeadline(1000))
        val remoteCommands = runner.commands.filter {
            it.last().contains("/owner") || it.last().contains("then dd if=")
        }
        assertTrue(remoteCommands.filterNot { "-tt" in it }.all {
            it.subList(7, 11) == listOf("shell", "-T", "sh", "-c")
        })
        assertTrue(runner.wrapper.substringBefore("trap 'cleanup").contains("/proc/"))
    }

    @Test
    fun `recovered child is force stopped if its interrupt fails`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.launchReturnGate = CompletableDeferred()
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        val start = launch { session.start(RequestDeadline(1000)) }
        runner.launched.await()
        start.cancelAndJoin()
        runner.signalCode = 1
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertEquals(1, runner.forcedRemoteStops)
    }

    @Test
    fun `transport exit cannot suppress remote forced cleanup after a failed signal`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        session.start(RequestDeadline(1000))
        runner.exited.complete(RecordingCommandResult(1, byteArrayOf(), "transport ended"))
        runner.signalCode = 1
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertEquals(1, runner.forcedRemoteStops)
        assertFalse(runner.live)
    }

    @Test
    fun `cancellation while launcher returns still recovers the unique ownership record`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.launchReturnGate = CompletableDeferred()
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        val start = launch { session.start(RequestDeadline(1000)) }
        runner.launched.await()
        start.cancelAndJoin()
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertEquals(1, runner.signals)
        assertFalse(runner.live)
    }

    @Test
    fun `premature exit stops startup polling without waiting for container initialization`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.initialBytes = byteArrayOf()
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        val start = async { runCatching { session.start(RequestDeadline(1000)) } }
        runner.readStarted.await()
        runner.completeExit(0)
        assertIs<RecordingException>(withTimeoutOrNull(100) { start.await() }?.exceptionOrNull())
    }

    @Test
    fun `endpoint uses Adam property then environment then default precedence`() {
        assertEquals(5037, AndroidRecordingEndpoint.capture({ null }, { null }).port)
        val environment = mapOf("ANDROID_ADB_SERVER_PORT" to "5040")
        assertEquals(5040, AndroidRecordingEndpoint.capture({ null }, environment::get).port)
        assertEquals(5041, AndroidRecordingEndpoint.capture({ "5041" }, environment::get).port)
        assertEquals(5040, AndroidRecordingEndpoint.capture({ "invalid" }, environment::get).port)
        assertEquals(5040, AndroidRecordingEndpoint.capture({ "65535" }, environment::get).port)
        assertEquals(5041, AndroidRecordingEndpoint.capture({ "0x13b1" }, environment::get).port)
    }

    @Test
    fun `contradictory adb socket and host overrides are rejected`() {
        for (override in listOf("ADB_SERVER_SOCKET" to "tcp:remote:5037",
            "ADB_SERVER_SOCKET" to "tcp:localhost:5040", "ANDROID_ADB_SERVER_ADDRESS" to "remote")) {
            assertFailsWith<RecordingException> {
                AndroidRecordingEndpoint.capture({ null }, mapOf(override)::get)
            }
        }
        assertEquals(5037, AndroidRecordingEndpoint.capture({ null },
            mapOf("ADB_SERVER_SOCKET" to "tcp:localhost:5037")::get).port)
    }

    @Test
    fun `contradictory requested serial never changes the first online target`() {
        val endpoint = AndroidRecordingEndpoint.capture({ null }, mapOf("ANDROID_SERIAL" to "serial-2")::get)
        assertFailsWith<RecordingException> { endpoint.validateSerial("serial-1") }
        endpoint.validateSerial("serial-2")
    }

    @Test
    fun `configured endpoint stays pinned if the property changes during recording`() = runTest {
        val previous = System.getProperty("ANDROID_ADB_SERVER_PORT")
        try {
            System.setProperty("ANDROID_ADB_SERVER_PORT", "5041")
            val runner = FakeAndroidRecordingProcesses()
            val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
            session.start(RequestDeadline(1000))
            System.setProperty("ANDROID_ADB_SERVER_PORT", "5042")
            session.finish(RequestDeadline(1000))
            runner.commands.filter { it.first() == "/fake/adb" }.forEach { assertEquals("5041", it[4]) }
        } finally {
            if (previous == null) System.clearProperty("ANDROID_ADB_SERVER_PORT")
            else System.setProperty("ANDROID_ADB_SERVER_PORT", previous)
        }
    }

    @Test
    fun `cancelled startup recovers remote ownership before PID notification is consumed`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.notifyPid = false
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        val finalized = CompletableDeferred<Path>()
        val start = launch {
            try { session.start(RequestDeadline(1000)) }
            finally { finalized.complete(session.finish(RequestDeadline(1000))) }
        }
        runner.launched.await()
        start.cancelAndJoin()
        assertEquals(directory.resolve("recording.mp4"), finalized.await())
        assertEquals(1, runner.signals)
        assertFalse(runner.live)
        assertTrue(runner.exited.isCompleted)
        val signal = runner.commands.indexOfFirst { "-T" in it && it.last().contains("kill -INT") }
        assertTrue(runner.commands.take(signal).any { it.last().contains("/owner") })
    }

    @Test
    fun `startup timeout leaves no polling task behind`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.initialBytes = byteArrayOf()
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        val start = async { runCatching {
            session.start(RequestDeadline(100) { testScheduler.currentTime * 1_000_000 })
        } }
        runner.launched.await()
        assertIs<RecordingException>(withTimeoutOrNull(200) { start.await() }?.exceptionOrNull())
        val count = runner.reads.size
        assertEquals(100, testScheduler.currentTime)
        session.finish(RequestDeadline(1000))
        assertEquals(count, runner.reads.size)
    }

    @Test
    fun `failed pull retains remote media and identifies recovery target`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.pullCode = 1
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        session.start(RequestDeadline(1000))
        val failure = assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertTrue(failure.message.orEmpty().contains("${runner.remote}/recording.mp4"))
        assertTrue(failure.message.orEmpty().contains("serial-1"))
        assertFalse(runner.removed)
        assertTrue(Files.list(directory).use { it.count() == 0L })
        val repeated = assertFailsWith<RecordingException> { session.finish(RequestDeadline(0)) }
        assertEquals(failure.message, repeated.message)
    }

    @Test
    fun `invalid transferred video is never published`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.finalBytes = initializedMovie()
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        session.start(RequestDeadline(1000))
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertTrue(Files.list(directory).use { it.count() == 0L })
        assertTrue(runner.removed)
    }

    @Test
    fun `signal failure forces only the remotely verified child`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.signalCode = 1
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        session.start(RequestDeadline(1000))
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertEquals(1, runner.forcedRemoteStops)
        assertFalse(runner.live)
        assertTrue(runner.commands.filter { "-T" in it && it.last().contains("kill -") }.all {
            it.last().contains("/owner") && it.last().contains("/proc/")
        })
    }

    @Test
    fun `disconnected target cannot use host exit as remote finalization proof`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        session.start(RequestDeadline(1000))
        runner.disconnected = true
        runner.exited.complete(RecordingCommandResult(1, byteArrayOf(), "device disconnected"))
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertTrue(runner.live)
        assertFalse(runner.commands.any { "pull" in it })
    }

    @Test
    fun `wrong ownership record cannot signal or delete another recording`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        session.start(RequestDeadline(1000))
        runner.ownerToken = "unrelated"
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertEquals(0, runner.signals)
        assertEquals(0, runner.forcedRemoteStops)
        assertFalse(runner.removed)
        assertEquals(1, runner.forcedTransportStops)
    }

    @Test
    fun `stop timeout reserves cleanup inside the supplied deadline`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.signalDelay = 2000
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        session.start(RequestDeadline(1000))
        val deadline = RequestDeadline(1000) { testScheduler.currentTime * 1_000_000 }
        assertFailsWith<RecordingException> { session.finish(deadline) }
        assertEquals(1, runner.forcedRemoteStops)
        assertTrue(testScheduler.currentTime <= 1000)
        assertTrue(runner.finalizationDeadlines.all { it === deadline })
    }

    @Test
    fun `launch errors become recording failures with temporary cleanup`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.launchFailure = IOException("missing executable")
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        assertFailsWith<RecordingException> { session.start(RequestDeadline(1000)) }
        assertFailsWith<RecordingException> { session.finish(RequestDeadline(1000)) }
        assertTrue(Files.list(directory).use { it.count() == 0L })
    }

    @Test
    fun `zero and nonzero premature exits are terminal even before the observer resumes`() = runTest {
        for (code in listOf(0, 7)) {
            val runner = FakeAndroidRecordingProcesses()
            val destination = directory.resolve("exit-$code")
            val session = AndroidRecordingSession(backgroundScope, destination, 150_000, runner)
            session.start(RequestDeadline(1000))
            runner.completeExit(code)
            assertEquals(destination.resolve("recording.mp4"), session.finish(RequestDeadline(1000)))
            assertEquals(RecordingExit(code, "early exit"), withTimeoutOrNull(100) { session.terminal.await() })
            assertEquals(0, runner.signals)
        }
    }

    @Test
    fun `exit after the finalization snapshot remains premature`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        session.start(RequestDeadline(1000))
        runner.afterExitSnapshot = { runner.completeExit(0) }
        session.finish(RequestDeadline(1000))
        assertEquals(RecordingExit(0, "early exit"), withTimeoutOrNull(100) { session.terminal.await() })
        assertEquals(0, runner.signals)
    }

    @Test
    fun `exit during startup prevents readiness`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.readGate = CompletableDeferred()
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        val start = async { runCatching { session.start(RequestDeadline(1000)) } }
        runner.readStarted.await()
        runner.completeExit(0)
        runner.readGate?.complete(Unit)
        assertIs<RecordingException>(start.await().exceptionOrNull())
    }

    @Test
    fun `missing adb is an execution failure before discovery`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.executable = ""
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        assertFailsWith<RecordingException> { session.start(RequestDeadline(1000)) }
        assertEquals(1, runner.commands.size)
    }

    @Test
    fun `failed discovery never starts a recorder`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        runner.discoveryCode = 1
        val session = AndroidRecordingSession(backgroundScope, directory, 150_000, runner)
        assertFailsWith<RecordingException> { session.start(RequestDeadline(1000)) }
        assertTrue(runner.wrapper.isEmpty())
    }

    @Test
    fun `unsupported duration is rejected before launching`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        val session = AndroidRecordingSession(backgroundScope, directory, 180_001, runner)
        assertFailsWith<RecordingException> { session.start(RequestDeadline(1000)) }
        assertTrue(runner.wrapper.isEmpty())
    }

    @Test
    fun `initialized headers establish readiness before frames are flushed`() = runTest {
        val runner = FakeAndroidRecordingProcesses()
        val session = AndroidRecordingSession(backgroundScope, directory, 155_000, runner)
        session.start(RequestDeadline(1000))
        assertFalse(runner.exited.isCompleted)
        assertTrue(runner.reads.isNotEmpty())
        assertTrue(runner.reads.all { (offset, size) -> offset + size <= initializedMovie().size })
        val readsAtStartup = runner.reads.size
        val path = session.finish(RequestDeadline(1000))
        assertEquals(directory.resolve("recording.mp4"), path)
        assertContentEquals(movieWithMetadataBeforeVideo(), Files.readAllBytes(path))
        assertEquals(readsAtStartup, runner.reads.size)
        assertEquals(1, runner.signals)
        assertTrue(runner.removed)
        runCurrent()
        assertFalse(session.terminal.isCompleted)
        assertEquals(path, session.finish(RequestDeadline(0)))
        assertEquals(1, runner.commands.count { it.last() == "devices" })
        runner.commands.filter { it.first() == "/fake/adb" }.forEach {
            assertEquals(listOf("-H", "127.0.0.1", "-P", "5037"), it.subList(1, 5))
            if (it.last() != "devices") assertEquals(listOf("-s", "serial-1"), it.subList(5, 7))
        }
        assertTrue(runner.wrapper.indexOf("trap ") < runner.wrapper.indexOf("\n/system/bin/screenrecord"))
        assertTrue(runner.wrapper.contains("--time-limit 155"))
        assertTrue(runner.wrapper.contains("wait"))
    }
}

@OptIn(kotlinx.coroutines.InternalForInheritanceCoroutinesApi::class)
internal class FakeAndroidRecordingProcesses : RecordingProcessRunner {
    val commands = mutableListOf<List<String>>()
    val reads = mutableListOf<Pair<Long, Int>>()
    val exited = CompletableDeferred<RecordingCommandResult>()
    var wrapper = ""
    var token = ""
    var remote = ""
    var signals = 0
    var removed = false
    var live = true
    var remoteCode = 0
    var executable = "/fake/adb"
    var discoveryCode = 0
    var afterExitSnapshot: (() -> Unit)? = null
    var readGate: CompletableDeferred<Unit>? = null
    val readStarted = CompletableDeferred<Unit>()
    val launched = CompletableDeferred<Unit>()
    var notifyPid = true
    var initialBytes = initializedMovie()
    var finalBytes = movieWithMetadataBeforeVideo()
    var pullCode = 0
    var signalCode = 0
    var signalDelay = 0L
    var forcedRemoteStops = 0
    var forcedTransportStops = 0
    var disconnected = false
    var ownerToken: String? = null
    var launchFailure: Exception? = null
    var launchReturnGate: CompletableDeferred<Unit>? = null
    var helpCode = 0
    var notificationPid = "1234"
    var stopCode = 0
    var statusDelay = 0L
    var pendingOwnershipReads = 0
    var statusFixture: ((String, RequestDeadline) -> RecordingCommandResult)? = null
    val finalizationDeadlines = mutableListOf<RequestDeadline>()

    fun completeExit(code: Int) {
        live = false
        remoteCode = code
        exited.complete(RecordingCommandResult(code, byteArrayOf(), "early exit"))
    }

    override suspend fun start(
        scope: CoroutineScope,
        command: List<String>,
        deadline: RequestDeadline,
        stderrReadyLine: String?,
    ): OwnedRecordingProcess {
        commands += command
        wrapper = command.last()
        remote = Regex("/data/local/tmp/visiontest-[a-f0-9-]+").find(wrapper)?.value ?: error("Missing owned path")
        token = remote.substringAfter("visiontest-")
        launchFailure?.let { throw it }
        launched.complete(Unit)
        launchReturnGate?.await()
        return object : OwnedRecordingProcess {
            override val pid = 2_000_000_000L
            override val firstStdoutLine = CompletableDeferred<String>().also {
                if (notifyPid) it.complete("$token $notificationPid")
            }
            override val stderrReady = CompletableDeferred(Unit)
            override val exit = object : Deferred<RecordingCommandResult> by exited {
                override val isCompleted: Boolean
                    get() {
                        val snapshot = exited.isCompleted
                        val callback = afterExitSnapshot
                        afterExitSnapshot = null
                        callback?.invoke()
                        return snapshot
                    }
            }
            override fun isAlive() = !exited.isCompleted
            override suspend fun interrupt(deadline: RequestDeadline) = error("Signal remote PID, not adb")
            override suspend fun forceStop(deadline: RequestDeadline) {
                forcedTransportStops++
                finalizationDeadlines += deadline
                exited.complete(RecordingCommandResult(137, byteArrayOf(), "transport closed"))
            }
        }
    }

    override suspend fun run(
        command: List<String>,
        deadline: RequestDeadline,
        stdoutLimitBytes: Int,
    ): RecordingCommandResult {
        deadline.remainingMillis()
        commands += command
        val script = command.last()
        if (disconnected) return RecordingCommandResult(1, byteArrayOf(), "device disconnected")
        if (script.contains("kill -") || "pull" in command || script.contains("rm ")) finalizationDeadlines += deadline
        return when {
            command.first() == "/bin/sh" -> RecordingCommandResult(0, "$executable\n".toByteArray(), "")
            script == "devices" -> RecordingCommandResult(discoveryCode,
                "List of devices attached\noffline-1\toffline\nserial-1\tdevice\nserial-2\tdevice\n".toByteArray(),
                "discovery failed")
            script == "--help" -> RecordingCommandResult(helpCode,
                "--time-limit TIME\nMaximum / default is 180.\n".toByteArray(), "help diagnostic")
            "pull" in command -> {
                Files.write(Path.of(script), finalBytes)
                RecordingCommandResult(pullCode, byteArrayOf(), "transfer failed")
            }
            script.contains("kill -INT") -> interruptRemote()
            else -> inspectRemote(script, stdoutLimitBytes, deadline)
        }
    }

    private suspend fun inspectRemote(
        script: String,
        stdoutLimitBytes: Int,
        deadline: RequestDeadline,
    ): RecordingCommandResult {
        if (script.contains("/owner") && !script.contains("rm ") && !script.contains("kill -")) {
            statusFixture?.let { return it(script, deadline) }
        }
        return if (script.contains("/owner") && pendingOwnershipReads > 0) {
            pendingOwnershipReads--
            RecordingCommandResult(1, byteArrayOf(), "owner not published")
        } else RecordingCommandResult(0, remoteCommand(script, stdoutLimitBytes), "")
    }

    private suspend fun remoteCommand(script: String, stdoutLimitBytes: Int): ByteArray = when {
            script.contains("then dd if=") -> readRange(script, stdoutLimitBytes)
            script.contains("kill -KILL") -> {
                forcedRemoteStops++
                completeExit(137)
                byteArrayOf()
            }
            script.contains("rm ") -> { removed = true; byteArrayOf() }
            script.contains("/owner") -> {
                delay(statusDelay)
                "${ownerToken ?: token} 1234 ${if (live) "LIVE" else "STOPPED $remoteCode"}\n".toByteArray()
            }
            else -> error("Unexpected command $script")
        }

    private suspend fun interruptRemote(): RecordingCommandResult {
        signals++
        delay(signalDelay)
        if (signalCode == 0) {
            live = false
            remoteCode = stopCode
            exited.complete(RecordingCommandResult(stopCode, byteArrayOf(), "ERROR codec failed; fallback succeeded"))
        }
        return RecordingCommandResult(signalCode, byteArrayOf(), "signal diagnostic")
    }

    private suspend fun readRange(script: String, stdoutLimitBytes: Int): ByteArray {
        readStarted.complete(Unit)
        readGate?.await()
        val offset = checkNotNull(Regex("skip=([0-9]+)").find(script)).groupValues[1].toLong()
        val count = checkNotNull(Regex("count=([0-9]+)").find(script)).groupValues[1].toInt()
        reads += offset to count
        assertEquals(count, stdoutLimitBytes)
        val bytes = initialBytes
        return if (offset >= bytes.size) byteArrayOf()
        else bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + count))
    }
}

private class CompletionTransition(
    private val directory: Path,
    private val runner: FakeAndroidRecordingProcesses,
    private val zombie: Boolean = false,
    private val publish: Boolean = true,
) {
    var exitedReads = 0
    val deadlines = mutableListOf<RequestDeadline>()

    init {
        Files.createDirectories(directory.resolve("proc/1234"))
        Files.writeString(directory.resolve("owner"), "${runner.token} 1234\n")
        Files.writeString(directory.resolve("proc/1234/cmdline"),
            "/system/bin/screenrecord\u0000--time-limit\u0000150\u0000$directory/recording.mp4\u0000")
    }

    fun inspect(script: String, deadline: RequestDeadline): RecordingCommandResult {
        deadlines += deadline
        if (!runner.live) {
            exitedReads++
            if (zombie) {
                Files.writeString(directory.resolve("proc/1234/cmdline"), "")
                Files.writeString(directory.resolve("proc/1234/status"), "State:\tZ (zombie)\n")
            } else {
                Files.deleteIfExists(directory.resolve("proc/1234/cmdline"))
                Files.deleteIfExists(directory.resolve("proc/1234"))
            }
            when (exitedReads) {
                2 -> {
                    Files.writeString(directory.resolve("done"), "")
                    Files.writeString(directory.resolve("done.tmp"), "")
                }
                3 -> Files.writeString(directory.resolve("done.tmp"), "0\n")
                4 -> if (publish) Files.move(directory.resolve("done.tmp"), directory.resolve("done"),
                    StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val local = unquoteRemoteScript(script).replace(runner.remote, directory.toString())
            .replace("/proc/", "$directory/proc/")
        return runLocalShell(local)
    }
}

private fun unquoteRemoteScript(script: String) = script.removeSurrounding("'").replace("'\\''", "'")

private fun runLocalShell(script: String): RecordingCommandResult {
    val process = ProcessBuilder("/bin/sh", "-c", script).start()
    try {
        check(process.waitFor(5, TimeUnit.SECONDS)) { "Local recording protocol script timed out" }
        return RecordingCommandResult(process.exitValue(), process.inputStream.readBytes(),
            process.errorStream.readBytes().decodeToString())
    } finally {
        if (process.isAlive) process.destroyForcibly().waitFor()
    }
}
