package com.example.visiontest.cli

import com.example.visiontest.CommandExecutionException
import com.example.visiontest.common.RequestDeadline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeoutException

internal data class FlowObservationReport(
    val kind: String,
    val status: String,
    val result: JsonObject? = null,
    val error: FlowError? = null,
    val exitCode: Int = 1,
)

internal data class FlowObservationOptions(
    val collector: FlowObservationCollector? = null,
    val onFailure: List<String> = emptyList(),
    val evidenceTimeoutMs: Long = 5000,
    val observe: List<String> = emptyList(),
) {
    suspend fun finish(execution: FlowReport, overall: RequestDeadline): FlowReport {
        val observations = if (execution.success && observe.isNotEmpty()) {
            requireNotNull(collector).collect(observe, overall, stopOnFailure = true)
        } else FlowObservationCollector.skipped(observe)
        val failedObservation = observations.firstOrNull { it.status == "failed" }
        val failure = execution.error ?: failedObservation?.error
        val evidence = if (failure != null && onFailure.isNotEmpty()) {
            requireNotNull(collector).collect(onFailure, RequestDeadline(evidenceTimeoutMs))
        } else FlowObservationCollector.skipped(onFailure)
        return execution.copy(success = failure == null, error = failure,
            exitCode = if (execution.success) failedObservation?.exitCode ?: 0 else execution.exitCode,
            observations = observations, evidence = evidence)
    }
}

internal class FlowObservationCollector(
    private val platform: Platform,
    private val components: Lazy<ComponentHolder>,
    private val bundleId: String? = null,
    private val artifacts: Path = Path.of("flow-artifacts", UUID.randomUUID().toString()).toAbsolutePath(),
) {
    companion object {
        val kinds = setOf("screenshot", "hierarchy", "interactive-elements")
        fun skipped(requested: List<String>): List<FlowObservationReport> =
            requested.map { FlowObservationReport(it, "skipped") }
    }

    @Suppress("TooGenericExceptionCaught") // Each requested observation must retain its own failure.
    suspend fun collect(
        requested: List<String>,
        deadline: RequestDeadline,
        stopOnFailure: Boolean = false,
    ): List<FlowObservationReport> {
        val records = mutableListOf<FlowObservationReport>()
        var stopped = false
        for (kind in requested) {
            if (stopped) {
                records += FlowObservationReport(kind, "skipped")
                continue
            }
            val record = try {
                val budget = deadline.remainingMillis()
                val result = withContext(deadline) {
                    withTimeoutOrNull(budget) {
                        observe(kind).also { deadline.remainingMillis() }
                    } ?: throw TimeoutException("Flow observation deadline exceeded")
                }
                result
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val mapped = cliFailure(error)
                FlowObservationReport(kind, "failed", error = FlowError(error.javaClass.simpleName,
                    mapped.stderr ?: "Observation failed"), exitCode = mapped.exitCode)
            }
            records += record
            if (record.status == "failed" && stopOnFailure) stopped = true
            if (!stopped) {
                stopped = try { deadline.remainingMillis(); false } catch (_: TimeoutException) { true }
            }
        }
        return records
    }

    private suspend fun observe(kind: String): FlowObservationReport = when (kind) {
        "screenshot" -> screenshot()
        "hierarchy" -> hierarchy()
        "interactive-elements" -> interactiveElements()
        else -> error("Unknown observation '$kind'")
    }

    private suspend fun screenshot(): FlowObservationReport {
        val path = artifact("png")
        val result = when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.captureScreenshotResult(path.toString())
            Platform.Ios -> components.value.iosAutomationRegistrar.captureScreenshotResult(path.toString())
        }
        return if (result.success) succeeded("screenshot", buildJsonObject {
            put("success", true)
            put("path", checkNotNull(result.path))
        }) else failed("screenshot", buildJsonObject { put("success", false); put("error", result.message) },
            result.message)
    }

    private suspend fun hierarchy(): FlowObservationReport {
        val raw = when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.getUiHierarchy()
            Platform.Ios -> components.value.iosAutomationRegistrar.getUiHierarchy(bundleId)
        }
        val response = automationResult(raw)
        if (response.isRpcError) return rpcFailure("hierarchy", response.value)
        val result = response.value
        val success = result.requiredSuccess()
        val error = result.optionalError()
        return if (!success) failed("hierarchy", buildJsonObject {
            put("success", false)
            error?.let { put("error", it) }
        }, error ?: "Hierarchy collection failed") else saveHierarchy(result)
    }

    private suspend fun saveHierarchy(result: JsonObject): FlowObservationReport {
        val xml = result["hierarchy"] as? JsonPrimitive
        if (xml?.isString != true) throw CommandExecutionException("Automation result.hierarchy must be a string")
        val path = artifact("xml")
        withContext(Dispatchers.IO) {
            Files.createDirectories(artifacts)
            Files.writeString(path, xml.content)
        }
        return succeeded("hierarchy", buildJsonObject { put("success", true); put("path", path.toString()) })
    }

    private suspend fun interactiveElements(): FlowObservationReport {
        val raw = when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.getInteractiveElements()
            Platform.Ios -> components.value.iosAutomationRegistrar.getInteractiveElements(bundleId = bundleId)
        }
        val result = Json.parseToJsonElement(inspectionOutput(raw, true,
            InspectionCommand.INTERACTIVE_ELEMENTS, platform)) as JsonObject
        if (result.containsKey("error") && result["error"] is JsonObject) {
            return rpcFailure("interactive-elements", result)
        }
        return if (result.requiredSuccess()) succeeded("interactive-elements", result)
            else failed("interactive-elements", result,
                result.optionalError() ?: "Interactive element collection failed")
    }

    private fun artifact(extension: String): Path = artifacts.resolve("${UUID.randomUUID()}.$extension")
    private fun succeeded(kind: String, result: JsonObject) = FlowObservationReport(kind, "succeeded", result)
    private fun failed(kind: String, result: JsonObject, message: String) =
        FlowObservationReport(kind, "failed", result, FlowError("OperationFailure", message))
    private fun rpcFailure(kind: String, result: JsonObject): FlowObservationReport {
        val rpc = result.getValue("error") as JsonObject
        return FlowObservationReport(kind, "failed", result,
            FlowError("RpcError", (rpc.getValue("message") as JsonPrimitive).content))
    }
}

private fun JsonObject.requiredSuccess(): Boolean {
    val value = get("success") as? JsonPrimitive
    return value?.takeUnless { it.isString }?.booleanOrNull
        ?: throw CommandExecutionException("Automation result.success must be boolean")
}

private fun JsonObject.optionalError(): String? {
    val value = get("error") ?: return null
    if (value !is JsonPrimitive || !value.isString) {
        throw CommandExecutionException("Automation result.error must be a string")
    }
    return value.content
}
