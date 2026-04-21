package com.example.visiontest.cli

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
