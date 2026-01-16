package com.example.automationserver

import android.app.Instrumentation
import android.util.Log
import androidx.test.uiautomator.UiDevice
import com.example.automationserver.jsonrpc.JsonRpcError
import com.example.automationserver.jsonrpc.JsonRpcRequest
import com.example.automationserver.jsonrpc.JsonRpcResponse
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JSON-RPC 2.0 Server for instrumentation context.
 *
 * This version uses a pre-configured UiDevice with proper Instrumentation,
 * enabling UIAutomator operations to work correctly.
 */
class JsonRpcServerInstrumented(
    private val port: Int,
    uiDevice: UiDevice,
    instrumentation: Instrumentation
) {
    companion object {
        private const val TAG = "JsonRpcServerInstr"
    }

    private var server: ApplicationEngine? = null
    private val gson = Gson()
    private val uiAutomator = UiAutomatorBridgeInstrumented(uiDevice, instrumentation)

    val isRunning: Boolean
        get() = server != null

    /**
     * Starts the JSON-RPC server.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (server != null) {
            Log.w(TAG, "Server already running")
            return@withContext
        }

        Log.i(TAG, "Starting JSON-RPC server on port $port")

        server = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                }
            }

            routing {
                // Health check endpoint
                get("/health") {
                    call.respond(mapOf("status" to "ok", "port" to port))
                }

                // JSON-RPC endpoint
                post("/jsonrpc") {
                    try {
                        val requestText = call.receiveText()
                        Log.d(TAG, "Received request: $requestText")

                        val response = handleRequest(requestText)
                        call.respond(response)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling request", e)
                        call.respond(
                            JsonRpcResponse(
                                error = JsonRpcError.internalError(e.message),
                                id = null
                            )
                        )
                    }
                }
            }
        }.start(wait = false)

        Log.i(TAG, "JSON-RPC server started on port $port")
    }

    /**
     * Stops the JSON-RPC server.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        Log.i(TAG, "Stopping JSON-RPC server")
        server?.stop(1000, 2000)
        server = null
        Log.i(TAG, "JSON-RPC server stopped")
    }

    /**
     * Handles a JSON-RPC request and returns a response.
     */
    private fun handleRequest(requestText: String): JsonRpcResponse {
        val request = try {
            gson.fromJson(requestText, JsonRpcRequest::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON-RPC request", e)
            return JsonRpcResponse(error = JsonRpcError.parseError(e.message), id = null)
        }

        if (request == null) {
            Log.e(TAG, "Parsed JSON-RPC request is null")
            return JsonRpcResponse(error = JsonRpcError.parseError("Failed to parse request"), id = null)
        }

        Log.d(TAG, "Handling JSON-RPC method: ${request.method}")

        if (request.jsonrpc != "2.0") {
            return JsonRpcResponse(
                error = JsonRpcError.invalidRequest("Invalid jsonrpc version"),
                id = request.id
            )
        }

        return try {
            val result = executeMethod(request.method, request.params as? JsonObject)
            JsonRpcResponse(result = result, id = request.id)
        } catch (e: MethodNotFoundException) {
            JsonRpcResponse(error = JsonRpcError.methodNotFound(request.method), id = request.id)
        } catch (e: InvalidParamsException) {
            JsonRpcResponse(error = JsonRpcError.invalidParams(e.message), id = request.id)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing method ${request.method}", e)
            JsonRpcResponse(
                error = JsonRpcError.uiAutomatorError(e.message ?: "Unknown error"),
                id = request.id
            )
        }
    }

    /**
     * Executes a JSON-RPC method.
     */
    private fun executeMethod(method: String, params: JsonObject?): Any {
        Log.d(TAG, "Executing method: $method with params: $params")

        return when (method) {
            // UI Hierarchy methods
            "ui.dumpHierarchy" -> uiAutomator.dumpHierarchy()

            // Device methods
            "device.getInfo" -> uiAutomator.getDeviceInfo()
            "device.pressBack" -> uiAutomator.pressBack()
            "device.pressHome" -> uiAutomator.pressHome()

            // Click methods
            "ui.click" -> {
                val x = params?.get("x")?.asInt
                    ?: throw InvalidParamsException("Missing 'x' parameter")
                val y = params.get("y")?.asInt
                    ?: throw InvalidParamsException("Missing 'y' parameter")
                uiAutomator.click(x, y)
            }

            // Find element method
            "ui.findElement" -> {
                val text = params?.get("text")?.asString
                val textContains = params?.get("textContains")?.asString
                val resourceId = params?.get("resourceId")?.asString
                val className = params?.get("className")?.asString
                val contentDescription = params?.get("contentDescription")?.asString

                if (text == null && textContains == null && resourceId == null &&
                    className == null && contentDescription == null) {
                    throw InvalidParamsException("At least one selector required: text, textContains, resourceId, className, or contentDescription")
                }

                uiAutomator.findElement(
                    text = text,
                    textContains = textContains,
                    resourceId = resourceId,
                    className = className,
                    contentDescription = contentDescription
                )
            }

            else -> throw MethodNotFoundException(method)
        }
    }
}

class MethodNotFoundException(method: String) : Exception("Method not found: $method")
class InvalidParamsException(message: String) : Exception(message)
