package com.example.visiontest.tools

import com.example.visiontest.utils.ErrorHandler
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.Logger

/**
 * DSL scope for registering MCP tools with standardized timeout and error handling.
 *
 * Absorbs the repeated try/withTimeout/catch/handleToolError boilerplate
 * so each tool only provides name, description, schema, and the business logic.
 *
 * Since the MCP SDK's `addTool` handler is already a suspend function,
 * we use `withTimeout` directly — no `runBlocking` needed.
 */
class ToolScope(
    private val server: Server,
    private val logger: Logger,
    private val defaultTimeoutMs: Long = 10_000L
) {
    fun tool(
        name: String,
        description: String,
        inputSchema: Tool.Input = Tool.Input(),
        timeoutMs: Long = defaultTimeoutMs,
        handler: suspend (CallToolRequest) -> String
    ) {
        server.addTool(name, description, inputSchema) { request ->
            try {
                val result = withTimeout(timeoutMs) {
                    handler(request)
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                ErrorHandler.handleToolError(e, logger, name)
            }
        }
    }
}

// --- CallToolRequest extension helpers ---

fun CallToolRequest.requireString(key: String): String {
    return this.arguments[key]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("Missing required parameter '$key'")
}

fun CallToolRequest.requireInt(key: String): Int {
    val raw = this.requireString(key)
    return raw.toIntOrNull()
        ?: throw IllegalArgumentException("Parameter '$key' must be an integer, got '$raw'")
}

fun CallToolRequest.optionalString(key: String): String? {
    return this.arguments[key]?.jsonPrimitive?.content
}

fun CallToolRequest.optionalInt(key: String): Int? {
    val raw = this.optionalString(key) ?: return null
    return raw.toIntOrNull()
        ?: throw IllegalArgumentException("Parameter '$key' must be an integer, got '$raw'")
}
