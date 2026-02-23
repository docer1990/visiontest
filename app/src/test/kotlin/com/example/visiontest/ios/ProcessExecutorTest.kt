package com.example.visiontest.ios

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class ProcessExecutorTest {

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
