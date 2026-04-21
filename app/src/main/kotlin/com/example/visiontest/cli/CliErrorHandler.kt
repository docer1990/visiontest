package com.example.visiontest.cli

import com.example.visiontest.NoDeviceAvailableException
import com.example.visiontest.NoSimulatorAvailableException
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import kotlinx.coroutines.runBlocking
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
    val result = executeCliCommand(block)
    if (result.stdout != null) println(result.stdout)
    if (result.stderr != null) System.err.println(result.stderr)
    exitProcess(result.exitCode)
}

/**
 * Executes [block] and returns a [CliResult] without calling `exitProcess`.
 * This is the testable core of [runCliCommand].
 */
internal fun executeCliCommand(block: suspend () -> String): CliResult {
    return try {
        val output = runBlocking { block() }
        CliResult(ExitCode.Success.value, stdout = output, stderr = null)
    } catch (e: CliExit) {
        CliResult(e.code.value, stdout = null, stderr = e.message)
    } catch (e: NoDeviceAvailableException) {
        CliResult(ExitCode.DeviceNotFound.value, stdout = null, stderr = e.message)
    } catch (e: NoSimulatorAvailableException) {
        CliResult(ExitCode.DeviceNotFound.value, stdout = null, stderr = e.message)
    } catch (e: UsageError) {
        CliResult(ExitCode.UsageError.value, stdout = null, stderr = e.message)
    } catch (e: CliktError) {
        CliResult(ExitCode.UsageError.value, stdout = null, stderr = e.message)
    } catch (e: IllegalArgumentException) {
        CliResult(ExitCode.UsageError.value, stdout = null, stderr = e.message ?: "Invalid argument")
    } catch (e: Exception) {
        CliResult(ExitCode.GenericFailure.value, stdout = null, stderr = e.message ?: "Unknown error")
    }
}

/**
 * Checks that the automation server is reachable, throwing [CliExit] with
 * [ExitCode.ServerNotReachable] if not. CLI commands that require a running
 * server should call this before delegating to the extracted function.
 */
suspend fun requireServerRunning(isRunning: suspend () -> Boolean) {
    if (!isRunning()) {
        throw CliExit(
            ExitCode.ServerNotReachable,
            "Automation server is not running. Run 'start_automation_server' first."
        )
    }
}
