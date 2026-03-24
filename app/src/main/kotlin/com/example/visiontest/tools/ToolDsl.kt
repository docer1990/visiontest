package com.example.visiontest.tools

import com.example.visiontest.utils.ErrorHandler
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeoutException
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
            } catch (e: TimeoutCancellationException) {
                ErrorHandler.handleToolError(
                    TimeoutException("Tool '$name' timed out after ${timeoutMs}ms"),
                    logger, name
                )
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

fun CallToolRequest.optionalBoolean(key: String): Boolean? {
    val raw = this.optionalString(key) ?: return null
    return when (raw) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Parameter '$key' must be true or false, got '$raw'")
    }
}

private val VALID_DIRECTIONS = listOf("up", "down", "left", "right")

fun CallToolRequest.requireDirection(key: String = "direction"): String {
    val direction = this.requireString(key)
    if (direction.lowercase() !in VALID_DIRECTIONS) {
        throw IllegalArgumentException("Invalid direction '$direction'. Must be one of: ${VALID_DIRECTIONS.joinToString()}")
    }
    return direction
}
