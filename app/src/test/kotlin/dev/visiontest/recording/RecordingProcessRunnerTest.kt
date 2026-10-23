package dev.visiontest.recording

import dev.visiontest.common.RequestDeadline
import io.mockk.every
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecordingProcessRunnerTest {
    @Test
    fun `reader failures are reported without cancelling the session`() = runTest {
        val process = completedProcess("", "", 0)
        every { process.inputStream } returns object : InputStream() {
            override fun read(): Int = throw IOException("broken pipe")
        }
        val handle = JvmRecordingProcessRunner { process }.start(this, listOf("fixture"), RequestDeadline(1000))
        val failure = assertFailsWith<RecordingException> { handle.exit.await() }
        val rootCause = generateSequence<Throwable>(failure) { it.cause }.last()
        assertIs<IOException>(rootCause)
        assertEquals("broken pipe", rootCause.message)
        assertFailsWith<RecordingException> { handle.firstStdoutLine.await() }
    }

    @Test
    fun `returned handles survive caller cancellation until explicit finalization`() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = GatedProcess()
            val registered = CompletableDeferred<OwnedRecordingProcess>()
            val caller = launch {
                registered.complete(JvmRecordingProcessRunner { fixture.process }.start(
                    this@runTest, listOf("fixture"), RequestDeadline(1000),
                ))
                awaitCancellation()
            }
            val handle = registered.await()
            caller.cancel()
            caller.join()
            try {
                assertTrue(handle.isAlive())
                handle.forceStop(RequestDeadline(1000))
                handle.forceStop(RequestDeadline(1000))
                assertFalse(handle.isAlive())
                assertEquals(137, handle.exit.await().exitCode)
            } finally { fixture.finish() }
        }
    }

    @Test
    fun `readers can finish after the process exit notification`() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = GatedProcess()
            val handle = JvmRecordingProcessRunner { fixture.process }.start(
                this@runTest, listOf("fixture"), RequestDeadline(1000),
            )
            try {
                fixture.stdout.reading.await()
                fixture.stderr.reading.await()
                fixture.finish(closePipes = false)
                assertFalse(handle.exit.isCompleted)
                fixture.stdout.emit("tail\n")
                fixture.stderr.emit("diagnostic tail\n")
                fixture.stdout.finish()
                fixture.stderr.finish()
                val result = withTimeout(2000) { handle.exit.await() }
                assertEquals("tail\n", result.stdout.decodeToString())
                assertEquals("diagnostic tail\n", result.stderr)
            } finally { fixture.finish() }
        }
    }

    @Test
    fun `deadline expiry during launch reaps before handle handoff`() = runTest {
        val fixture = GatedProcess()
        var now = 0L
        val deadline = RequestDeadline(1000) { now }
        val runner = JvmRecordingProcessRunner { now = 1_000_000_000; fixture.process }
        try {
            assertFailsWith<TimeoutException> { runner.start(this, listOf("fixture"), deadline) }
            assertFalse(fixture.process.isAlive)
            assertTrue(fixture.reaped.isDone)
        } finally { fixture.finish() }
    }

    @Test
    fun `reader shutdown is bounded after process exit`() = runTest {
        withContext(Dispatchers.Default) {
            val stream = StubbornStream()
            val process = completedProcess("", "", 0)
            every { process.inputStream } returns stream
            val handle = JvmRecordingProcessRunner { process }.start(
                this@runTest, listOf("fixture"), RequestDeadline(1000),
            )
            try {
                withTimeout(2000) { assertFailsWith<RecordingException> { handle.exit.await() } }
                assertFailsWith<RecordingException> { handle.firstStdoutLine.await() }
            } finally { stream.finish() }
        }
    }

    @Test
    fun `cancellation does not wait for a stuck reader`() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = GatedProcess()
            val stream = StubbornStream()
            every { fixture.process.inputStream } returns stream
            val caller = async {
                JvmRecordingProcessRunner { fixture.process }.run(listOf("fixture"), RequestDeadline(5000))
            }
            stream.reading.await()
            caller.cancel()
            try {
                withTimeout(2000) { caller.join() }
                assertFalse(fixture.process.isAlive)
                assertTrue(fixture.reaped.isDone)
            } finally { stream.finish(); fixture.finish() }
        }
    }

    @Test
    fun `capture failure stops a command that has not exited`() = runTest {
        withContext(Dispatchers.Default) {
            supervisorScope {
                val fixture = GatedProcess()
                val caller = async {
                    JvmRecordingProcessRunner { fixture.process }.run(listOf("fixture"), RequestDeadline(5000), 4)
                }
                fixture.stdout.emit("overflow")
                try {
                    withTimeout(2000) { assertFailsWith<RecordingException> { caller.await() } }
                    assertFalse(fixture.process.isAlive)
                } finally { fixture.finish(); caller.cancel() }
            }
        }
    }

    @Test
    fun `owned Unix child receives SIGINT and is reaped`() = runTest {
        assumeTrue(File.separatorChar == '/' && File("/bin/kill").canExecute() && File("/bin/sh").canExecute())
        withContext(Dispatchers.Default) {
            val watchdog = Executors.newSingleThreadScheduledExecutor()
            var child: Process? = null
            try {
                val runner = JvmRecordingProcessRunner { command ->
                    ProcessBuilder(command).start().also { process ->
                        child = process
                        watchdog.schedule({ process.destroyForcibly() }, 5, TimeUnit.SECONDS)
                    }
                }
                val handle = runner.start(
                    this,
                    listOf(
                        "/bin/sh", "-c",
                        "trap 'printf interrupted; exit 0' INT; printf 'ready\\n'; while :; do :; done",
                    ),
                    RequestDeadline(1000),
                )
                withTimeout(4000) {
                    assertEquals("ready", handle.firstStdoutLine.await())
                    handle.interrupt(RequestDeadline(2000))
                    handle.interrupt(RequestDeadline(1000))
                    handle.forceStop(RequestDeadline(1000))
                    val result = handle.exit.await()
                    assertEquals(0, result.exitCode)
                    assertEquals("ready\ninterrupted", result.stdout.decodeToString())
                    assertFalse(handle.isAlive())
                    assertFalse(ProcessHandle.of(handle.pid).map { it.isAlive }.orElse(false))
                }
            } finally {
                child?.destroyForcibly()
                child?.waitFor(2, TimeUnit.SECONDS)
                watchdog.shutdownNow()
            }
        }
    }

    @Test
    fun `startup exceptions retain their cause`() = runTest {
        val cause = IOException("missing executable")
        val runner = JvmRecordingProcessRunner { throw cause }
        val failure = assertFailsWith<RecordingException> {
            runner.start(this, listOf("fixture"), RequestDeadline(1000))
        }
        assertIs<IOException>(failure.cause)
        assertEquals(cause.message, failure.cause?.message)
    }

    @Test
    fun `cancelled callers do not launch a process`() = runTest {
        var launched = false
        val runner = JvmRecordingProcessRunner { launched = true; completedProcess("", "", 0) }
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            runner.start(this@runTest, listOf("fixture"), RequestDeadline(1000))
        }
        assertFailsWith<CancellationException> {
            caller.await()
        }
        assertFalse(launched)
    }

    @Test
    fun `cancellation during launch reaps before handle handoff`() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = GatedProcess()
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val runner = JvmRecordingProcessRunner {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                fixture.process
            }
            val caller = async { runner.start(this@runTest, listOf("fixture"), RequestDeadline(5000)) }
            entered.await()
            caller.cancel()
            release.countDown()
            try {
                withTimeout(2000) { fixture.terminated.await() }
                caller.join()
                assertFalse(fixture.process.isAlive)
                assertTrue(fixture.reaped.isDone)
            } finally { fixture.finish() }
        }
    }

    @Test
    fun `cancellation after registration stops and reaps short commands`() = runTest {
        withContext(Dispatchers.Default) {
            val fixture = GatedProcess()
            val caller = async {
                JvmRecordingProcessRunner { fixture.process }.run(listOf("fixture"), RequestDeadline(5000))
            }
            fixture.stdout.reading.await()
            caller.cancel()
            try {
                withTimeout(2000) { fixture.terminated.await(); caller.join() }
                assertFalse(fixture.process.isAlive)
                assertTrue(fixture.reaped.isDone)
            } finally { fixture.finish() }
        }
    }

    @Test
    fun `short command timeout stops and reaps the process`() = runTest {
        withContext(Dispatchers.Default) {
            supervisorScope {
                val fixture = GatedProcess()
                val caller = async {
                    JvmRecordingProcessRunner { fixture.process }.run(listOf("fixture"), RequestDeadline(100))
                }
                try {
                    withTimeout(2000) { assertFailsWith<TimeoutException> { caller.await() } }
                    assertFalse(fixture.process.isAlive)
                    assertTrue(fixture.reaped.isDone)
                } finally { fixture.finish(); caller.cancel() }
            }
        }
    }

    @Test
    fun `machine readable stdout overflow fails instead of truncating`() = runTest {
        val runner = JvmRecordingProcessRunner { completedProcess("12345", "", 0) }
        val failure = assertFailsWith<RecordingException> {
            runner.run(listOf("fixture"), RequestDeadline(1000), stdoutLimitBytes = 4)
        }
        assertTrue(failure.message.orEmpty().contains("limit"))
    }

    @Test
    fun `long lived captures retain only bounded diagnostics`() = runTest {
        val handle = JvmRecordingProcessRunner {
            completedProcess("123\n" + "x".repeat(100_000), "Ready\n" + "d".repeat(100_000), 0)
        }.start(this, listOf("fixture"), RequestDeadline(1000), "Ready")
        val result = handle.exit.await()
        assertEquals(65_536, result.stdout.size)
        assertEquals(65_536, result.stderr.toByteArray().size)
        assertEquals("123", handle.firstStdoutLine.await())
        handle.stderrReady.await()
    }

    @Test
    fun `overlong readiness lines fail without cancelling the owner`() = runTest {
        for (isStdout in listOf(true, false)) {
            val longLine = "x".repeat(65_537) + "\n"
            val handle = JvmRecordingProcessRunner {
                completedProcess(if (isStdout) longLine else "123\n", if (isStdout) "Ready\n" else longLine, 0)
            }.start(this, listOf("fixture"), RequestDeadline(1000), "Ready")
            assertFailsWith<RecordingException> {
                if (isStdout) handle.firstStdoutLine.await() else handle.stderrReady.await()
            }
            assertFailsWith<RecordingException> { handle.exit.await() }
        }
    }

    @Test
    fun `readiness is retained before subscription`() = runTest {
        val process = completedProcess("123\n", "Recording started\n", 0)
        val handle = JvmRecordingProcessRunner { process }.start(
            this, listOf("fixture"), RequestDeadline(1000), "Recording started",
        )
        handle.exit.await()
        assertEquals("123", handle.firstStdoutLine.await())
        handle.stderrReady.await()
    }

    @Test
    fun `both pipes are inspected before process exit`() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val fixture = GatedProcess()
                val handle = JvmRecordingProcessRunner { fixture.process }.start(
                    this, listOf("fixture"), RequestDeadline(1000), "Recording started",
                )
                fixture.stdout.emit("123\nmore output\n")
                fixture.stderr.emit("diagnostic\nRecording started\n")
                assertEquals("123", handle.firstStdoutLine.await())
                handle.stderrReady.await()
                assertTrue(handle.isAlive())
                fixture.finish(7)
                val result = handle.exit.await()
                assertEquals("123\nmore output\n", result.stdout.decodeToString())
                assertEquals("diagnostic\nRecording started\n", result.stderr)
                assertEquals(7, result.exitCode)
            }
        }
    }

    @Test
    fun `EOF before readiness fails without cancelling the owner`() = runTest {
        val fixture = GatedProcess()
        val handle = JvmRecordingProcessRunner { fixture.process }.start(
            this, listOf("fixture"), RequestDeadline(1000), "Recording started",
        )
        try {
            fixture.stdout.finish()
            fixture.stderr.emit("not ready\n")
            fixture.stderr.finish()
            assertFailsWith<RecordingException> { handle.firstStdoutLine.await() }
            assertFailsWith<RecordingException> { handle.stderrReady.await() }
            assertTrue(handle.isAlive())
        } finally { fixture.finish(3) }
        assertEquals(3, handle.exit.await().exitCode)
    }

    @Test
    fun `command output stays captured`() = runTest {
        val process = completedProcess("machine-output\n", "diagnostic\n", 0)
        val runner = JvmRecordingProcessRunner { process }
        val result = runner.run(listOf("fixture-command"), RequestDeadline(1000))
        assertEquals("machine-output\n", result.stdout.decodeToString())
        assertEquals("diagnostic\n", result.stderr)
        assertEquals(0, result.exitCode)
    }
}
