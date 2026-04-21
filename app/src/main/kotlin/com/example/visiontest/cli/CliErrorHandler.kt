package com.example.visiontest.cli

import com.example.visiontest.NoDeviceAvailableException
import com.example.visiontest.NoSimulatorAvailableException
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Runs a CLI command [block], prints its result to stdout on success (exit 0),
 * and maps exceptions to the appropriate exit code on stderr.
 *
 * This is the single exit-code gateway for every CLI subcommand.
 */
fun runCliCommand(block: suspend () -> String): Nothing {
    try {
        val result = runBlocking { block() }
        println(result)
        exitProcess(ExitCode.Success.value)
    } catch (e: CliExit) {
        System.err.println(e.message)
        exitProcess(e.code.value)
    } catch (e: NoDeviceAvailableException) {
        System.err.println(e.message)
        exitProcess(ExitCode.DeviceNotFound.value)
    } catch (e: NoSimulatorAvailableException) {
        System.err.println(e.message)
        exitProcess(ExitCode.DeviceNotFound.value)
    } catch (e: UsageError) {
        System.err.println(e.message)
        exitProcess(ExitCode.UsageError.value)
    } catch (e: CliktError) {
        System.err.println(e.message)
        exitProcess(ExitCode.UsageError.value)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message ?: "Invalid argument")
        exitProcess(ExitCode.UsageError.value)
    } catch (e: Exception) {
        System.err.println(e.message ?: "Unknown error")
        exitProcess(ExitCode.GenericFailure.value)
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
