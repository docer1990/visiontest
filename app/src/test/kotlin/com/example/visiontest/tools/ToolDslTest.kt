package com.example.visiontest.tools

import com.example.visiontest.utils.ErrorHandler
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolDslTest {

    // ===== CallToolRequest extension helpers =====

    private fun request(vararg pairs: Pair<String, String>): CallToolRequest {
        val args = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
        return CallToolRequest(name = "test", arguments = args)
    }

    // --- requireString ---

    @Test
    fun `requireString returns value when present`() {
        val req = request("key" to "hello")
        assertEquals("hello", req.requireString("key"))
    }

    @Test
    fun `requireString throws when key missing`() {
        val req = request()
        val ex = assertFailsWith<IllegalArgumentException> { req.requireString("key") }
        assertTrue(ex.message!!.contains("Missing required parameter 'key'"))
    }

    // --- requireInt ---

    @Test
    fun `requireInt returns parsed int`() {
        val req = request("x" to "42")
        assertEquals(42, req.requireInt("x"))
    }

    @Test
    fun `requireInt throws on non-integer`() {
        val req = request("x" to "abc")
        val ex = assertFailsWith<IllegalArgumentException> { req.requireInt("x") }
        assertTrue(ex.message!!.contains("must be an integer"))
    }

    @Test
    fun `requireInt throws when key missing`() {
        val req = request()
        assertFailsWith<IllegalArgumentException> { req.requireInt("x") }
    }

    // --- optionalString ---

    @Test
    fun `optionalString returns value when present`() {
        val req = request("key" to "val")
        assertEquals("val", req.optionalString("key"))
    }

    @Test
    fun `optionalString returns null when missing`() {
        val req = request()
        assertNull(req.optionalString("key"))
    }

    // --- optionalInt ---

    @Test
    fun `optionalInt returns parsed int when present`() {
        val req = request("n" to "7")
        assertEquals(7, req.optionalInt("n"))
    }

    @Test
    fun `optionalInt returns null when missing`() {
        val req = request()
        assertNull(req.optionalInt("n"))
    }

    @Test
    fun `optionalInt throws on non-integer value`() {
        val req = request("n" to "abc")
        val ex = assertFailsWith<IllegalArgumentException> { req.optionalInt("n") }
        assertTrue(ex.message!!.contains("must be an integer"))
    }

    // --- optionalBoolean ---

    @Test
    fun `optionalBoolean returns true`() {
        val req = request("flag" to "true")
        assertEquals(true, req.optionalBoolean("flag"))
    }

    @Test
    fun `optionalBoolean returns false`() {
        val req = request("flag" to "false")
        assertEquals(false, req.optionalBoolean("flag"))
    }

    @Test
    fun `optionalBoolean returns null when missing`() {
        val req = request()
        assertNull(req.optionalBoolean("flag"))
    }

    @Test
    fun `optionalBoolean throws on invalid value`() {
        val req = request("flag" to "yes")
        val ex = assertFailsWith<IllegalArgumentException> { req.optionalBoolean("flag") }
        assertTrue(ex.message!!.contains("must be true or false"))
    }

    // --- requireDirection ---

    @Test
    fun `requireDirection accepts valid directions`() {
        for (dir in listOf("up", "down", "left", "right")) {
            val req = request("direction" to dir)
            assertEquals(dir, req.requireDirection())
        }
    }

    @Test
    fun `requireDirection is case-insensitive for validation`() {
        val req = request("direction" to "UP")
        assertEquals("UP", req.requireDirection())
    }

    @Test
    fun `requireDirection throws on invalid direction`() {
        val req = request("direction" to "diagonal")
        val ex = assertFailsWith<IllegalArgumentException> { req.requireDirection() }
        assertTrue(ex.message!!.contains("Invalid direction"))
    }

    @Test
    fun `requireDirection uses custom key`() {
        val req = request("swipeDir" to "left")
        assertEquals("left", req.requireDirection("swipeDir"))
    }

    // ===== ToolScope =====

    private val logger = LoggerFactory.getLogger(ToolDslTest::class.java)

    /**
     * Captures the handler registered via Server.addTool so we can invoke it directly,
     * bypassing Server's private handleCallTool.
     */
    private fun captureHandler(
        defaultTimeoutMs: Long = 10_000L,
        timeoutMs: Long? = null,
        handler: suspend (CallToolRequest) -> String
    ): suspend (CallToolRequest) -> CallToolResult {
        val server = mockk<Server>(relaxed = true)
        val handlerSlot = slot<suspend (CallToolRequest) -> CallToolResult>()

        val scope = ToolScope(server, logger, defaultTimeoutMs)
        scope.tool(
            name = "test_tool",
            description = "test",
            timeoutMs = timeoutMs ?: defaultTimeoutMs,
            handler = handler
        )

        verify {
            server.addTool(
                name = "test_tool",
                description = "test",
                inputSchema = any<Tool.Input>(),
                handler = capture(handlerSlot)
            )
        }

        return handlerSlot.captured
    }

    @Test
    fun `tool returns successful result`() = runBlocking {
        val captured = captureHandler { "Hello!" }

        val result = captured(request())
        val text = (result.content.first() as TextContent).text
        assertEquals("Hello!", text)
    }

    @Test
    fun `tool wraps handler exception via ErrorHandler`() = runBlocking {
        val captured = captureHandler {
            throw IllegalArgumentException("bad input")
        }

        val result = captured(request())
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains(ErrorHandler.ERROR_INVALID_ARG))
    }

    @Test
    fun `tool maps timeout to ERR_TIMEOUT`() = runBlocking {
        val captured = captureHandler(defaultTimeoutMs = 50L) {
            delay(5_000L)
            "never"
        }

        val result = captured(request())
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains(ErrorHandler.ERROR_TIMEOUT), "Expected ERR_TIMEOUT in: $text")
    }

    @Test
    fun `tool timeout message includes tool name`() = runBlocking {
        val captured = captureHandler(defaultTimeoutMs = 50L) {
            delay(5_000L)
            "never"
        }

        val result = captured(request())
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("test_tool"), "Expected tool name in: $text")
    }

    @Test
    fun `tool rethrows CancellationException for structured concurrency`() = runBlocking {
        val captured = captureHandler {
            throw CancellationException("job cancelled")
        }

        assertFailsWith<CancellationException> { captured(request()) }
    }

    @Test
    fun `tool respects per-tool timeout override`() = runBlocking {
        val captured = captureHandler(defaultTimeoutMs = 10_000L, timeoutMs = 50L) {
            delay(5_000L)
            "never"
        }

        val result = captured(request())
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains(ErrorHandler.ERROR_TIMEOUT), "Expected ERR_TIMEOUT in: $text")
    }
}
