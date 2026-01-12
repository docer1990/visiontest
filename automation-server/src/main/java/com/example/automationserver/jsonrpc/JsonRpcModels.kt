package com.example.automationserver.jsonrpc

import com.google.gson.JsonElement

/**
 * JSON-RPC 2.0 Request
 */
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
    val id: Any? = null
)

/**
 * JSON-RPC 2.0 Response
 */
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val result: Any? = null,
    val error: JsonRpcError? = null,
    val id: Any? = null
)

/**
 * JSON-RPC 2.0 Error
 */
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
) {
    companion object {
        // Standard JSON-RPC error codes
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        // Custom error codes (starting from -32000)
        const val UIAUTOMATOR_ERROR = -32000
        const val DEVICE_ERROR = -32001

        fun parseError(data: Any? = null) = JsonRpcError(PARSE_ERROR, "Parse error", data)
        fun invalidRequest(data: Any? = null) = JsonRpcError(INVALID_REQUEST, "Invalid Request", data)
        fun methodNotFound(method: String) = JsonRpcError(METHOD_NOT_FOUND, "Method not found: $method")
        fun invalidParams(data: Any? = null) = JsonRpcError(INVALID_PARAMS, "Invalid params", data)
        fun internalError(data: Any? = null) = JsonRpcError(INTERNAL_ERROR, "Internal error", data)
        fun uiAutomatorError(message: String) = JsonRpcError(UIAUTOMATOR_ERROR, message)
    }
}
