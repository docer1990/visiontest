package com.example.automationserver.jsonrpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonRpcModelsTest {

    // --- JsonRpcError factory methods ---

    @Test
    fun `parseError returns code -32700 with Parse error message`() {
        val error = JsonRpcError.parseError()
        assertEquals(JsonRpcError.PARSE_ERROR, error.code)
        assertEquals(-32700, error.code)
        assertEquals("Parse error", error.message)
        assertNull(error.data)
    }

    @Test
    fun `parseError with data sets data field`() {
        val error = JsonRpcError.parseError(data = "unexpected token")
        assertEquals(-32700, error.code)
        assertEquals("unexpected token", error.data)
    }

    @Test
    fun `invalidRequest returns code -32600`() {
        val error = JsonRpcError.invalidRequest()
        assertEquals(JsonRpcError.INVALID_REQUEST, error.code)
        assertEquals(-32600, error.code)
        assertEquals("Invalid Request", error.message)
        assertNull(error.data)
    }

    @Test
    fun `invalidRequest with data sets data field`() {
        val error = JsonRpcError.invalidRequest(data = "missing method")
        assertEquals("missing method", error.data)
    }

    @Test
    fun `methodNotFound returns code -32601 and includes method name`() {
        val error = JsonRpcError.methodNotFound("foo.bar")
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, error.code)
        assertEquals(-32601, error.code)
        assertEquals("Method not found: foo.bar", error.message)
        assertNull(error.data)
    }

    @Test
    fun `invalidParams returns code -32602`() {
        val error = JsonRpcError.invalidParams()
        assertEquals(JsonRpcError.INVALID_PARAMS, error.code)
        assertEquals(-32602, error.code)
        assertEquals("Invalid params", error.message)
        assertNull(error.data)
    }

    @Test
    fun `invalidParams with data sets data field`() {
        val error = JsonRpcError.invalidParams(data = "x must be positive")
        assertEquals("x must be positive", error.data)
    }

    @Test
    fun `internalError returns code -32603`() {
        val error = JsonRpcError.internalError()
        assertEquals(JsonRpcError.INTERNAL_ERROR, error.code)
        assertEquals(-32603, error.code)
        assertEquals("Internal error", error.message)
        assertNull(error.data)
    }

    @Test
    fun `internalError with data sets data field`() {
        val error = JsonRpcError.internalError(data = "stack overflow")
        assertEquals("stack overflow", error.data)
    }

    @Test
    fun `uiAutomatorError returns code -32000 with custom message`() {
        val error = JsonRpcError.uiAutomatorError("element not found")
        assertEquals(JsonRpcError.UIAUTOMATOR_ERROR, error.code)
        assertEquals(-32000, error.code)
        assertEquals("element not found", error.message)
        assertNull(error.data)
    }

    // --- JsonRpcError companion constants ---

    @Test
    fun `DEVICE_ERROR constant is -32001`() {
        assertEquals(-32001, JsonRpcError.DEVICE_ERROR)
    }

    // --- JsonRpcRequest defaults ---

    @Test
    fun `JsonRpcRequest defaults jsonrpc to 2_0`() {
        val request = JsonRpcRequest(method = "test")
        assertEquals("2.0", request.jsonrpc)
    }

    @Test
    fun `JsonRpcRequest defaults params to null`() {
        val request = JsonRpcRequest(method = "test")
        assertNull(request.params)
    }

    @Test
    fun `JsonRpcRequest defaults id to null`() {
        val request = JsonRpcRequest(method = "test")
        assertNull(request.id)
    }

    @Test
    fun `JsonRpcRequest preserves method`() {
        val request = JsonRpcRequest(method = "ui.dumpHierarchy")
        assertEquals("ui.dumpHierarchy", request.method)
    }

    // --- JsonRpcResponse defaults ---

    @Test
    fun `JsonRpcResponse defaults jsonrpc to 2_0`() {
        val response = JsonRpcResponse()
        assertEquals("2.0", response.jsonrpc)
    }

    @Test
    fun `JsonRpcResponse defaults result to null`() {
        val response = JsonRpcResponse()
        assertNull(response.result)
    }

    @Test
    fun `JsonRpcResponse defaults error to null`() {
        val response = JsonRpcResponse()
        assertNull(response.error)
    }

    @Test
    fun `JsonRpcResponse defaults id to null`() {
        val response = JsonRpcResponse()
        assertNull(response.id)
    }

    @Test
    fun `JsonRpcResponse with result preserves it`() {
        val response = JsonRpcResponse(result = mapOf("key" to "value"), id = 1)
        assertEquals(mapOf("key" to "value"), response.result)
        assertEquals(1, response.id)
    }

    @Test
    fun `JsonRpcResponse with error preserves it`() {
        val error = JsonRpcError.internalError()
        val response = JsonRpcResponse(error = error, id = 42)
        assertEquals(error, response.error)
        assertEquals(42, response.id)
    }
}
