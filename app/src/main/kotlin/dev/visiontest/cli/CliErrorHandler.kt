package dev.visiontest.cli

import dev.visiontest.NoDeviceAvailableException
import dev.visiontest.NoSimulatorAvailableException
import dev.visiontest.ServerNotRunningException
import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.system.exitProcess

/**
 * Result of executing a CLI command, before process exit.
 * Used internally for testability — tests inspect this instead of trapping `exitProcess`.
 */
data class CliResult(val exitCode: Int, val stdout: String?, val stderr: String?)

/**
 * Runs a CLI command [block], prints its result to stdout on success (exit 0),
 * and maps exceptions to the appropriate exit code on stderr.
 *
 * This is the single exit-code gateway for every CLI subcommand.
 */
fun runCliCommand(block: suspend () -> String): Nothing {
    return runCliCommand(block) {}
}

internal fun runCliCommand(block: suspend () -> String, beforeExit: () -> Any?): Nothing {
    val result = executeCliCommand(block)
    if (result.stdout != null) println(result.stdout)
    if (result.stderr != null) System.err.println(result.stderr)
    beforeExit()
    exitProcess(result.exitCode)
}

/**
 * Executes [block] and returns a [CliResult] without calling `exitProcess`.
 * This is the testable core of [runCliCommand].
 */
internal fun executeCliCommand(block: suspend () -> String): CliResult {
    return executeCliResultCommand { CliResult(ExitCode.Success.value, block(), null) }
}

@Suppress("TooGenericExceptionCaught") // Every command failure passes through the shared exit-code mapping.
internal fun executeCliResultCommand(block: suspend () -> CliResult): CliResult =
    executeCliResultCommand(EmptyCoroutineContext, block)

@Suppress("TooGenericExceptionCaught") // Every command failure passes through the shared exit-code mapping.
internal fun executeCliResultCommand(
    executionContext: CoroutineContext,
    block: suspend () -> CliResult,
): CliResult = try {
    runBlocking(executionContext) { block() }
} catch (error: Exception) {
    executionContext[dev.visiontest.common.DispatchEvidence]?.operationFailed()
    cliFailure(error)
}

internal fun runCliResultCommand(block: suspend () -> CliResult, beforeExit: () -> Any? = {}): Nothing {
    val result = executeCliResultCommand(block = block)
    result.stdout?.let(::println)
    result.stderr?.let(System.err::println)
    beforeExit()
    exitProcess(result.exitCode)
}

internal fun cliFailure(error: Exception): CliResult {
    val code = when (error) {
        is CliExit -> error.code.value
        is ServerNotRunningException -> ExitCode.ServerNotReachable.value
        is NoDeviceAvailableException, is NoSimulatorAvailableException -> ExitCode.DeviceNotFound.value
        is CliktError, is IllegalArgumentException -> ExitCode.UsageError.value
        else -> ExitCode.GenericFailure.value
    }
    val message = error.message ?: when (error) {
        is IllegalArgumentException -> "Invalid argument"
        is CliExit, is ServerNotRunningException, is NoDeviceAvailableException,
        is NoSimulatorAvailableException, is CliktError -> null
        else -> "Unknown error"
    }
    return CliResult(code, null, message)
}

/**
 * Checks that the automation server is reachable, throwing [CliExit] with
 * [ExitCode.ServerNotReachable] if not. CLI commands that require a running
 * server should call this before delegating to the extracted registrar function.
 *
 * Note: the extracted registrar functions also check `isServerRunning()` internally
 * and throw [ServerNotRunningException] if the server drops mid-operation. The CLI
 * pre-check here provides a fast-fail with the proper exit code, while the registrar
 * check closes the TOCTOU gap (both are caught by [executeCliCommand]).
 */
suspend fun requireServerRunning(isRunning: suspend () -> Boolean) {
    if (!isRunning()) {
        throw CliExit(
            ExitCode.ServerNotReachable,
            "Automation server is not running. Run 'start_automation_server' first."
        )
    }
}
