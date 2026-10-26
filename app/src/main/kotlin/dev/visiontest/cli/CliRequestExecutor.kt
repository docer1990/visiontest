package dev.visiontest.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.output.Localization
import com.github.ajalt.clikt.output.ParameterFormatter
import dev.visiontest.performance.TraceRuntime
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Fresh parsing and trace lifetime for each request; printing and process exit belong to the caller. */
internal class CliRequestExecutor(private val entryNs: () -> Long = System::nanoTime) {
    fun execute(
        args: Array<String>,
        context: CliExecutionContext,
        components: (() -> ComponentHolder)? = null,
        executionContext: CoroutineContext = EmptyCoroutineContext,
    ): CliResult {
        val trace = TraceRuntime("cli", entryNs(), context.diagnostic)
        var result = CliResult(0, null, null)
        try {
            VisionTestCli(trace, components, resultRunner = { block ->
                result = executeCliResultCommand(executionContext, block)
            }, readStdin = context.readStdin).parse(args)
        } catch (error: CliExit) {
            traceFailure(trace, "other", error)
            result = CliResult(error.code.value, null, error.message)
        } catch (error: UsageError) {
            traceFailure(trace, error.context?.command?.commandName ?: "other", error)
            result = CliResult(ExitCode.UsageError.value, null, error.formatMessage(
                error.context?.localization ?: object : Localization {}, parameterFormatter))
        } catch (error: PrintHelpMessage) {
            traceFailure(trace, "help", if (error.error) error else null)
            result = CliResult(if (error.error) 1 else 0, error.context?.command?.getFormattedHelp(), null)
        } catch (error: PrintMessage) {
            traceFailure(trace, "version", null)
            result = CliResult(error.statusCode, error.message, null)
        } catch (error: CliktError) {
            traceFailure(trace, "other", error)
            result = CliResult(ExitCode.GenericFailure.value, null, error.message.orEmpty())
        } finally {
            trace.finish()
        }
        return result
    }

    private fun traceFailure(trace: TraceRuntime, operation: String, failure: Throwable?) {
        runBlocking {
            runCatching { trace.cliInvocation(operation) { if (failure != null) throw failure } }
        }
    }

    private companion object {
        val parameterFormatter = object : ParameterFormatter {
            override fun formatOption(name: String) = name
            override fun formatArgument(name: String) = "<$name>"
            override fun formatSubcommand(name: String) = name
        }
    }
}
