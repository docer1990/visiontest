package com.example.visiontest.cli

import com.example.visiontest.CommandExecutionException
import com.example.visiontest.common.RequestDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.concurrent.TimeoutException

internal class FlowExecutor {
    @Suppress("TooGenericExceptionCaught") // Execution reports retain every mapped command failure.
    suspend fun execute(
        platform: Platform,
        steps: List<FlowPreparedStep>,
        stepTimeoutMs: Long = 35000,
        timeoutMs: Long = 120000,
    ): FlowReport {
        val overall = RequestDeadline(timeoutMs)
        val results = mutableListOf<FlowStepReport>()
        var failure: FlowError? = null
        var exitCode = 0
        for (step in steps) {
            if (failure != null) {
                results += FlowStepReport(step.id, step.command, "skipped", "not_executed")
                continue
            }
            try {
                val budget = minOf(stepTimeoutMs, overall.remainingMillis())
                val deadline = RequestDeadline(budget)
                val result = withContext(deadline) {
                    withTimeoutOrNull(budget) { evaluate(platform, step, step.execute()) }
                        ?: throw TimeoutException("Flow step deadline exceeded")
                }
                results += FlowStepReport(step.id, step.command, if (result.success) "succeeded" else "failed",
                    result = result.result, error = result.error)
                if (!result.success) {
                    failure = result.error
                    exitCode = 1
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val mapped = cliFailure(error)
                failure = FlowError(error.javaClass.simpleName, mapped.stderr ?: "Unknown error")
                exitCode = mapped.exitCode
                results += FlowStepReport(step.id, step.command, "failed", error = failure)
            }
        }
        return FlowReport(platform, failure == null, exitCode, results, failure)
    }

    private fun evaluate(platform: Platform, step: FlowPreparedStep, raw: String): FlowOutcome {
        val response = if (step.kind == FlowStepKind.SCREENSHOT) AutomationResponse(parseScreenshot(raw), false)
            else automationResult(raw)
        val result = response.value
        if (response.isRpcError) {
            val rpc = result.getValue("error") as JsonObject
            return FlowOutcome(false, result, FlowError("RpcError", (rpc.getValue("message") as JsonPrimitive).content))
        }
        return when (step.kind) {
            FlowStepKind.ACTION, FlowStepKind.SCREENSHOT -> {
                val success = result.boolean("success")
                val error = result["error"]
                if (error != null && (error !is JsonPrimitive || !error.isString)) {
                    throw CommandExecutionException("Automation result.error must be a string")
                }
                val failure = if (success) null else FlowError("OperationFailure",
                    (error as? JsonPrimitive)?.content ?: "Operation failed")
                FlowOutcome(success, result, failure)
            }
            FlowStepKind.WAIT -> {
                val found = Json.parseToJsonElement(inspectionOutput(raw, true,
                    InspectionCommand.FIND_ELEMENT, platform)) as JsonObject
                val satisfied = found.boolean("found") == (step.condition != "gone")
                val value = buildJsonObject {
                    found.forEach { (key, field) -> put(key, field) }
                    put("condition", step.condition)
                    put("satisfied", satisfied)
                }
                FlowOutcome(satisfied, value,
                    if (satisfied) null else FlowError("OperationFailure", "Element wait condition was not satisfied"))
            }
        }
    }

    private fun parseScreenshot(raw: String): JsonObject {
        val result = parseScreenshotObject(raw)
        val success = result.boolean("success")
        val error = result["error"]
        if (error != null && (error !is JsonPrimitive || !error.isString)) {
            throw CommandExecutionException("Screenshot result.error must be a string")
        }
        val path = result["path"] as? JsonPrimitive
        if (success && (path?.isString != true || !Path.of(path.content).isAbsolute)) {
            throw CommandExecutionException("Screenshot result.path must be an absolute path")
        }
        return buildJsonObject {
            put("success", success)
            if (success) put("path", path!!) else error?.let { put("error", it) }
        }
    }

    private fun parseScreenshotObject(raw: String): JsonObject = try {
        Json.parseToJsonElement(raw) as? JsonObject
            ?: throw CommandExecutionException("Screenshot response must be an object")
    } catch (error: IllegalArgumentException) {
        throw CommandExecutionException("Malformed screenshot response: ${error.message}").also { it.initCause(error) }
    }
}

private data class FlowOutcome(val success: Boolean, val result: JsonObject, val error: FlowError?)

private fun JsonObject.boolean(name: String): Boolean {
    val value = get(name) as? JsonPrimitive
    return value?.takeUnless { it.isString }?.booleanOrNull
        ?: throw CommandExecutionException("Automation result.$name must be boolean")
}
