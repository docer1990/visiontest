package com.example.visiontest.ios

import com.example.visiontest.performance.OperationOutcome
import com.example.visiontest.performance.TraceEvent
import com.example.visiontest.performance.TraceOutcome
import com.example.visiontest.performance.TraceRecorder
import com.example.visiontest.performance.TraceStage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class ProcessExecutorTest {
    @Test
    fun `traced nonzero subprocess retains normally returned failure`() = runBlocking {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        val command = arrayOf("sh", "-c", "printf synthetic-secret; printf synthetic-error >&2; exit 7")
        val expected = ProcessExecutor().execute(*command)
        val actual = trace.invocation("ios_get_device_info", "ios") {
            ProcessExecutor(trace = trace).execute(*command)
        }
        assertEquals(expected, actual)
        val span = events.single { it.stage == TraceStage.SIMCTL }
        assertEquals(TraceOutcome.RETURNED, span.outcome)
        assertEquals(OperationOutcome.FAILURE, span.operationOutcome)
        assertTrue(events.none { it.toJson().toString().contains("synthetic") })
    }

    @Test
    fun `traced process timeout retains exception and classifies timeout`() = runBlocking {
        val events = mutableListOf<TraceEvent>()
        val trace = TraceRecorder(emit = { events.add(it); Unit })
        val error = assertFailsWith<CommandTimeoutException> {
            trace.invocation("ios_get_device_info", "ios") {
                ProcessExecutor(timeoutMillis = 50L, trace = trace).execute("sleep", "60")
            }
        }
        assertEquals("Command timed out: sleep 60", error.message)
        assertEquals(2, events.size)
        assertTrue(events.all { it.outcome == TraceOutcome.TIMEOUT && it.errorCategory == null })
    }

    @Test
    fun `legacy public device and process constructors retain JVM noarg`() {
        listOf(ProcessExecutor::class.java, IOSSimulator::class.java, IOSManager::class.java,
            com.example.visiontest.android.Android::class.java).forEach {
            assertTrue(it.getConstructor().newInstance() != null)
        }
        val loggerType = org.slf4j.Logger::class.java
        ProcessExecutor::class.java.getConstructor(java.lang.Long.TYPE, loggerType)
        IOSSimulator::class.java.getConstructor(ProcessExecutor::class.java, ProcessExecutor::class.java, loggerType)
        IOSManager::class.java.getConstructor(loggerType)
        com.example.visiontest.android.Android::class.java.getConstructor(
            java.lang.Long.TYPE, java.lang.Long.TYPE, loggerType,
        )
        ProcessExecutor(1000L)
        IOSSimulator(ProcessExecutor(), ProcessExecutor())
        IOSManager(org.slf4j.LoggerFactory.getLogger(javaClass))
        com.example.visiontest.android.Android(1000L, 1000L)
    }

    private val executor = ProcessExecutor(timeoutMillis = 10000L)

    @Test
    fun `execute returns exit code 0 and output for echo`() = runBlocking {
        val result = executor.execute("echo", "hello")
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.output)
    }

    @Test
    fun `execute returns non-zero exit code for false command`() = runBlocking {
        val result = executor.execute("false")
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `execute throws exception for non-existent command`() = runBlocking {
        // ProcessBuilder throws IOException when the command doesn't exist
        try {
            executor.execute("nonexistent_command_that_does_not_exist_12345")
            fail("Expected an exception for non-existent command")
        } catch (e: Exception) {
            // IOException (or wrapped variant) expected from ProcessBuilder
            assertTrue(e is java.io.IOException || e.cause is java.io.IOException,
                "Expected IOException but got ${e::class.simpleName}: ${e.message}")
        }
    }

    @Test
    fun `execute captures multi-line output`() = runBlocking {
        val result = executor.execute("printf", "line1\nline2\nline3")
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("line1"))
        assertTrue(result.output.contains("line2"))
        assertTrue(result.output.contains("line3"))
    }

    @Test
    fun `execute returns empty output for silent command`() = runBlocking {
        val result = executor.execute("true")
        assertEquals(0, result.exitCode)
        assertEquals("", result.output)
    }

    @Test
    fun `execute throws CommandTimeoutException for long-running command`() = runBlocking {
        val shortTimeoutExecutor = ProcessExecutor(timeoutMillis = 500L)

        val exception = assertFailsWith<CommandTimeoutException> {
            shortTimeoutExecutor.execute("sleep", "60")
        }
        assertTrue(exception.message!!.contains("timed out"))
    }

    @Test
    fun `execute captures stderr separately from stdout`() = runBlocking {
        val result = executor.execute("bash", "-c", "echo stdout; echo stderr >&2")
        assertEquals(0, result.exitCode)
        assertEquals("stdout", result.output)
        assertEquals("stderr", result.errorOutput)
    }
}
