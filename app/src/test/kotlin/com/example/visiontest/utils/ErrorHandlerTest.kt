package com.example.visiontest.utils

import com.example.visiontest.*
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ErrorHandlerTest {

    private val logger = LoggerFactory.getLogger(ErrorHandlerTest::class.java)

    // --- handleToolError: exception-to-error-code mappings ---

    @Test
    fun `NoDeviceAvailableException maps to ERROR_DEVICE_NOT_FOUND`() {
        val result = ErrorHandler.handleToolError(
            NoDeviceAvailableException("no device"), logger, "test"
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("[Code: ${ErrorHandler.ERROR_DEVICE_NOT_FOUND}]"))
    }

    @Test
    fun `CommandExecutionException maps to ERROR_COMMAND_FAILED with exit code`() {
        val result = ErrorHandler.handleToolError(
            CommandExecutionException("failed", exitCode = 42), logger, "test"
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("[Code: ${ErrorHandler.ERROR_COMMAND_FAILED}]"))
        assertTrue(text.contains("code: 42"))
    }

    @Test
    fun `PackageNotFoundException maps to ERROR_PACKAGE_NOT_FOUND`() {
        val result = ErrorHandler.handleToolError(
            PackageNotFoundException("com.test.app"), logger, "test"
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("[Code: ${ErrorHandler.ERROR_PACKAGE_NOT_FOUND}]"))
        assertTrue(text.contains("com.test.app"))
    }

    @Test
    fun `AdbInitializationException maps to ERROR_ADB_INIT`() {
        val result = ErrorHandler.handleToolError(
            AdbInitializationException("adb failed"), logger, "test"
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("[Code: ${ErrorHandler.ERROR_ADB_INIT}]"))
    }

    @Test
    fun `NoSimulatorAvailableException maps to ERROR_NO_SIMULATOR`() {
        val result = ErrorHandler.handleToolError(
            NoSimulatorAvailableException("no sim"), logger, "test"
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("[Code: ${ErrorHandler.ERROR_NO_SIMULATOR}]"))
    }

    @Test
    fun `IOSSimulatorException maps to ERROR_IOS_SIMULATOR`() {
        val result = ErrorHandler.handleToolError(
            IOSSimulatorException("sim error"), logger, "test"
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("[Code: ${ErrorHandler.ERROR_IOS_SIMULATOR}]"))
    }

    @Test
    fun `AppNotFoundException maps to ERROR_APP_NOT_FOUND`() {
        val result = ErrorHandler.handleToolError(
            AppNotFoundException("com.missing.app"), logger, "test"
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("[Code: ${ErrorHandler.ERROR_APP_NOT_FOUND}]"))
    }

    @Test
    fun `AppInfoException maps to ERROR_APP_INFO_FAILED`() {
        val result = ErrorHandler.handleToolError(
            AppInfoException("info failed"), logger, "test"
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("[Code: ${ErrorHandler.ERROR_APP_INFO_FAILED}]"))
    }

    @Test
    fun `AppListException maps to ERROR_APP_LIST_FAILED`() {
        val result = ErrorHandler.handleToolError(
            AppListException("list failed"), logger, "test"
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("[Code: ${ErrorHandler.ERROR_APP_LIST_FAILED}]"))
    }

    @Test
    fun `TimeoutException maps to ERROR_TIMEOUT`() {
        val result = ErrorHandler.handleToolError(
            TimeoutException("timed out"), logger, "test"
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("[Code: ${ErrorHandler.ERROR_TIMEOUT}]"))
    }

    @Test
    fun `IllegalArgumentException maps to ERROR_INVALID_ARG`() {
        val result = ErrorHandler.handleToolError(
            IllegalArgumentException("bad arg"), logger, "test"
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("[Code: ${ErrorHandler.ERROR_INVALID_ARG}]"))
    }

    @Test
    fun `Unknown exception maps to ERROR_UNKNOWN`() {
        val result = ErrorHandler.handleToolError(
            RuntimeException("something unexpected"), logger, "test"
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("[Code: ${ErrorHandler.ERROR_UNKNOWN}]"))
    }

    @Test
    fun `result content contains error message and code suffix`() {
        val result = ErrorHandler.handleToolError(
            NoDeviceAvailableException("gone"), logger, "test"
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.endsWith("[Code: ${ErrorHandler.ERROR_DEVICE_NOT_FOUND}]"))
        assertTrue(text.contains(ErrorHandler.DEVICE_NOT_FOUND))
    }

    // --- retryOperation ---

    @Test
    fun `retryOperation succeeds on first attempt`() = runBlocking {
        var attempts = 0
        val result = ErrorHandler.retryOperation {
            attempts++
            "success"
        }
        assertEquals("success", result)
        assertEquals(1, attempts)
    }

    @Test
    fun `retryOperation succeeds on second attempt after one failure`() = runBlocking {
        var attempts = 0
        val result = ErrorHandler.retryOperation(initialDelayMs = 10) {
            attempts++
            if (attempts < 2) throw RuntimeException("fail")
            "success"
        }
        assertEquals("success", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `retryOperation succeeds on third attempt after two failures`() = runBlocking {
        var attempts = 0
        val result = ErrorHandler.retryOperation(initialDelayMs = 10) {
            attempts++
            if (attempts < 3) throw RuntimeException("fail")
            "success"
        }
        assertEquals("success", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `retryOperation throws last exception after maxAttempts exhausted`() = runBlocking {
        var attempts = 0
        val exception = assertFailsWith<RuntimeException> {
            ErrorHandler.retryOperation(maxAttempts = 3, initialDelayMs = 10) {
                attempts++
                throw RuntimeException("fail #$attempts")
            }
        }
        assertEquals(3, attempts)
        assertEquals("fail #3", exception.message)
    }

    @Test
    fun `retryOperation with maxAttempts 1 throws immediately`() = runBlocking {
        var attempts = 0
        assertFailsWith<RuntimeException> {
            ErrorHandler.retryOperation(maxAttempts = 1, initialDelayMs = 10) {
                attempts++
                throw RuntimeException("fail")
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `retryOperation uses default maxAttempts of 3`() = runBlocking {
        var attempts = 0
        assertFailsWith<RuntimeException> {
            ErrorHandler.retryOperation(initialDelayMs = 10) {
                attempts++
                throw RuntimeException("fail")
            }
        }
        assertEquals(3, attempts)
    }
}
