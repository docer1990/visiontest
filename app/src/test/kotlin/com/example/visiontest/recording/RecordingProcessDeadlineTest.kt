package com.example.visiontest.recording

import com.example.visiontest.common.RequestDeadline
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingProcessDeadlineTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `Android transfer cancellation shares the total finalization deadline`() = runTest {
        val native = FakeAndroidRecordingProcesses().also { it.signalDelay = 28000 }
        val fixture = GatedProcess()
        var signalled = false
        every { fixture.process.destroyForcibly() } answers { signalled = true; fixture.process }
        val transfer = JvmRecordingProcessRunner(StandardTestDispatcher(testScheduler)) { fixture.process }
        val runner = object : RecordingProcessRunner by native {
            override suspend fun run(command: List<String>, deadline: RequestDeadline, stdoutLimitBytes: Int)
                : RecordingCommandResult = if ("pull" in command) transfer.run(command, deadline, stdoutLimitBytes)
                else native.run(command, deadline, stdoutLimitBytes)
        }
        val session = AndroidRecordingSession(backgroundScope, directory, 150000, runner)
        try {
            session.start(RequestDeadline(1000))
            val failure = assertFailsWith<RecordingException> {
                session.finish(RequestDeadline(30000) { testScheduler.currentTime * 1_000_000 })
            }
            assertTrue(failure.message.orEmpty().contains("retained ${native.remote}/recording.mp4 on serial-1"))
            assertTrue(signalled)
            assertEquals(30000, testScheduler.currentTime)
        } finally { fixture.finish() }
    }

    @Test
    fun `forced termination reserves time to reap within a short command deadline`() = runTest {
        supervisorScope {
            val fixture = GatedProcess()
            var signalledAt: Long? = null
            every { fixture.process.destroyForcibly() } answers {
                signalledAt = testScheduler.currentTime
                backgroundScope.launch { delay(10); fixture.finish(137) }
                fixture.process
            }
            val runner = JvmRecordingProcessRunner(StandardTestDispatcher(testScheduler)) { fixture.process }
            try {
                val result = async {
                    runner.run(listOf("fixture"), RequestDeadline(100) { testScheduler.currentTime * 1_000_000 })
                }
                assertFailsWith<TimeoutException> { result.await() }
                assertTrue(checkNotNull(signalledAt) < 100)
                assertTrue(testScheduler.currentTime <= 100)
                assertTrue(fixture.reaped.isDone)
            } finally { fixture.finish() }
        }
    }

    @Test
    fun `cancellation cannot renew the remaining process exit budget`() = runTest {
        val fixture = GatedProcess()
        val launched = CompletableDeferred<Unit>()
        var signalled = false
        every { fixture.process.destroyForcibly() } answers { signalled = true; fixture.process }
        val runner = JvmRecordingProcessRunner(StandardTestDispatcher(testScheduler)) {
            launched.complete(Unit)
            fixture.process
        }
        val caller = async {
            runner.run(listOf("fixture"), RequestDeadline(100) { testScheduler.currentTime * 1_000_000 })
        }
        try {
            launched.await()
            advanceTimeBy(50)
            caller.cancel()
            caller.join()
            assertTrue(signalled)
            assertEquals(100, testScheduler.currentTime)
        } finally { fixture.finish() }
    }

    @Test
    fun `expired launch still sends forced termination without a renewed wait`() = runTest {
        val fixture = GatedProcess()
        var signalled = false
        every { fixture.process.destroyForcibly() } answers { signalled = true; fixture.process }
        var now = 0L
        val deadline = RequestDeadline(100) { now }
        val runner = JvmRecordingProcessRunner(StandardTestDispatcher(testScheduler)) {
            now = 100_000_000
            fixture.process
        }
        try {
            assertFailsWith<TimeoutException> { runner.start(backgroundScope, listOf("fixture"), deadline) }
            assertTrue(signalled)
            assertEquals(0, testScheduler.currentTime)
        } finally { fixture.finish(); runCurrent() }
    }
}
