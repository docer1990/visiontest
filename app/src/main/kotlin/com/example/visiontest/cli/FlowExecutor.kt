package com.example.visiontest.cli

import com.example.visiontest.common.automationResult
import com.example.visiontest.CommandExecutionException
import com.example.visiontest.common.AutomationResponse
import com.example.visiontest.common.RequestDeadline
import com.example.visiontest.common.strictJsonObject
import com.example.visiontest.recording.RecordingSession
import com.example.visiontest.recording.RecordingException
import com.example.visiontest.recording.RecordingExit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.TimeoutException

internal class FlowExecutor(private val deadlineFactory: (Long) -> RequestDeadline = { RequestDeadline(it) }) {
    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    suspend fun execute(
        platform: Platform,
        steps: List<FlowPreparedStep>,
        stepTimeoutMs: Long = 35000,
        timeoutMs: Long = 120000,
        observationOptions: FlowObservationOptions = FlowObservationOptions(),
        recordingOptions: FlowRecordingOptions? = null,
    ): FlowReport = coroutineScope {
        val overall = deadlineFactory(timeoutMs)
        var session: RecordingSession? = null
        if (recordingOptions != null) {
            try {
                val budget = minOf(STARTUP_TIMEOUT_MS, overall.remainingMillis())
                val deadline = deadlineFactory(budget)
                val evidenceBudget = if (observationOptions.onFailure.isNotEmpty()) {
                    observationOptions.evidenceTimeoutMs
                } else 0
                withTimeoutOrNull(budget) {
                    val allocated = recordingOptions.factory.value.create(this@coroutineScope, platform,
                        recordingOptions.artifacts, timeoutMs + evidenceBudget + FINALIZATION_TIMEOUT_MS)
                    session = allocated
                    deadline.remainingMillis()
                    allocated.start(deadline)
                    deadline.remainingMillis()
                } ?: throw TimeoutException("Flow recording startup deadline exceeded")
            } catch (error: CancellationException) {
                session?.let { cleanupStartup(it) }
                throw error
            } catch (error: Exception) {
                session?.let { cleanupStartup(it) }
                currentCoroutineContext().ensureActive()
                return@coroutineScope startupFailure(platform, steps, observationOptions, error)
            }
        }
        val coordinator = FlowRecordingCoordinator(session)
        val execution = executeSteps(platform, steps, stepTimeoutMs, overall, coordinator)
        val observed = observationOptions.finish(execution, overall, deadlineFactory, coordinator)
        val recording = session?.let {
            val budget = minOf(FINALIZATION_TIMEOUT_MS, overall.remainingMillis())
            val deadline = deadlineFactory(budget)
            val path = withTimeoutOrNull(budget) {
                it.finish(deadline).also { deadline.remainingMillis() }
            } ?: throw TimeoutException("Flow recording finalization deadline exceeded")
            coordinator.report(path)
        }
        observed.withRecording(recording)
    }

    private companion object {
        const val STARTUP_TIMEOUT_MS = 10000L
        const val FINALIZATION_TIMEOUT_MS = 30000L
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun cleanupStartup(session: RecordingSession) = withContext(NonCancellable) {
        try {
            withTimeoutOrNull(FINALIZATION_TIMEOUT_MS) {
                session.finish(deadlineFactory(FINALIZATION_TIMEOUT_MS))
            }
        } catch (_: Exception) {
            // Startup remains the primary failure even when partial-recorder cleanup fails.
        }
    }

    private fun startupFailure(
        platform: Platform,
        steps: List<FlowPreparedStep>,
        observations: FlowObservationOptions,
        error: Exception,
    ): FlowReport {
        val mapped = cliFailure(error)
        val failure = FlowError(error.javaClass.simpleName, mapped.stderr ?: "Recording startup failed")
        val exitCode = if (mapped.exitCode == ExitCode.DeviceNotFound.value) {
            mapped.exitCode
        } else ExitCode.GenericFailure.value
        return FlowReport(platform, false, exitCode,
            steps.map { FlowStepReport(it.id, it.command, "skipped", "not_executed") }, failure,
            observations = FlowObservationCollector.skipped(observations.observe),
            evidence = FlowObservationCollector.skipped(observations.onFailure),
            recording = FlowRecordingReport("failed", "start", error = failure))
    }

    @Suppress("TooGenericExceptionCaught") // Execution reports retain every mapped command failure.
    private suspend fun executeSteps(
        platform: Platform,
        steps: List<FlowPreparedStep>,
        stepTimeoutMs: Long,
        overall: RequestDeadline,
        coordinator: FlowRecordingCoordinator,
    ): FlowReport {
        val results = mutableListOf<FlowStepReport>()
        var failure: FlowError? = null
        var exitCode = 0
        for (step in steps) {
            if (failure != null) {
                results += FlowStepReport(step.id, step.command, "skipped", "not_executed")
                continue
            }
            var deadline: RequestDeadline? = null
            var started = false
            try {
                val budget = minOf(stepTimeoutMs, overall.remainingMillis())
                val stepDeadline = deadlineFactory(budget)
                deadline = stepDeadline
                val result = coordinator.run(stepDeadline, budget, "Flow step deadline exceeded") {
                    started = true
                    evaluate(platform, step, step.execute())
                }
                results += FlowStepReport(step.id, step.command, if (result.success) "succeeded" else "failed",
                    result = result.result, error = result.error)
                if (!result.success) {
                    failure = result.error
                    exitCode = 1
                }
                if (failure == null && coordinator.failure != null) {
                    failure = coordinator.failure?.asFlowError()
                    exitCode = 1
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val mapped = cliFailure(error)
                failure = FlowError(error.javaClass.simpleName, mapped.stderr ?: "Unknown error")
                exitCode = mapped.exitCode
                val uncertain = step.kind == FlowStepKind.ACTION && deadline?.actionDispatched == true
                val skipped = !started && coordinator.failure != null
                results += interruptedStep(step, skipped, uncertain, failure)
            }
        }
        return FlowReport(platform, failure == null, exitCode, results, failure)
    }

    private fun interruptedStep(step: FlowPreparedStep, skipped: Boolean, uncertain: Boolean, error: FlowError) =
        when {
            skipped -> FlowStepReport(step.id, step.command, "skipped", "not_executed")
            uncertain -> FlowStepReport(step.id, step.command, "uncertain", "unknown", error = error)
            else -> FlowStepReport(step.id, step.command, "failed", error = error)
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
        if (success && !path.isAbsolutePath()) {
            throw CommandExecutionException("Screenshot result.path must be an absolute path")
        }
        return buildJsonObject {
            put("success", success)
            if (success) put("path", path!!) else error?.let { put("error", it) }
        }
    }

    private fun parseScreenshotObject(raw: String): JsonObject = try {
        strictJsonObject(raw)
    } catch (error: IllegalArgumentException) {
        throw CommandExecutionException("Malformed screenshot response: ${error.message}").also { it.initCause(error) }
    }
}

internal class FlowRecordingCoordinator(private val session: RecordingSession?) {
    var failure: RecordingException? = null
        private set

    suspend fun report(path: Path): FlowRecordingReport {
        if (session?.terminal?.isCompleted == true) exited(session.terminal.await())
        return FlowRecordingReport(if (failure == null) "succeeded" else "failed",
            phase = failure?.let { "record" }, path = path.toAbsolutePath().toString(), error = failure?.asFlowError())
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun <T : Any> run(
        deadline: RequestDeadline,
        budget: Long,
        timeoutMessage: String,
        operation: suspend () -> T,
    ): T = coroutineScope {
        if (session == null) return@coroutineScope withContext(deadline) {
            withTimeoutOrNull(budget) {
                operation().also { deadline.remainingMillis() }
            } ?: throw TimeoutException(timeoutMessage)
        }
        failure?.let { throw it }
        // Cancelling the return handoff must not erase an already validated response.
        var validated: T? = null
        var operationFailure: Exception? = null
        val active = launch(start = CoroutineStart.LAZY) {
            try {
                withContext(deadline) {
                    withTimeoutOrNull(budget) {
                        operation().also {
                            deadline.remainingMillis()
                            validated = it
                        }
                    } ?: throw TimeoutException(timeoutMessage)
                }
            } catch (error: Exception) {
                operationFailure = error
            }
        }
        select {
            session.terminal.onAwait { exited(it) }
            active.onJoin { }
        }
        if (failure != null) active.cancelAndJoin()
        else operationFailure?.let { throw it }
        validated ?: throw (failure ?: operationFailure ?: error("Flow operation produced no outcome"))
    }

    private fun exited(exit: RecordingExit) {
        if (failure == null) failure = RecordingException(
            "Recorder exited before intentional stop (exit ${exit.exitCode}): ${exit.diagnostics}")
    }
}

private fun RecordingException.asFlowError() = FlowError(javaClass.simpleName, message ?: "Recording failed")

private fun FlowReport.withRecording(recording: FlowRecordingReport?): FlowReport {
    val failure = error ?: recording?.error
    return copy(recording = recording, success = failure == null, error = failure,
        exitCode = if (success && failure != null) 1 else exitCode)
}

private data class FlowOutcome(val success: Boolean, val result: JsonObject, val error: FlowError?)

private fun JsonObject.boolean(name: String): Boolean {
    val value = get(name) as? JsonPrimitive
    return value?.takeUnless { it.isString }?.booleanOrNull
        ?: throw CommandExecutionException("Automation result.$name must be boolean")
}

private fun JsonPrimitive?.isAbsolutePath(): Boolean = try {
    this?.isString == true && Path.of(content).isAbsolute
} catch (_: InvalidPathException) {
    false
}
