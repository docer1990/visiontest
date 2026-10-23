package dev.visiontest.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context

abstract class PreparedCommand protected constructor(
    name: String,
    help: String,
    private val runner: CliCommandRunner,
) : CliktCommand(name = name, help = help) {
    protected var preparingForFlow: Boolean = false
        private set
    private var preparedExecution: (suspend () -> String)? = null

    protected abstract fun prepareExecution(): suspend () -> String

    protected open fun formatStandaloneOutput(response: String): String = response

    final override fun run() {
        if (preparingForFlow) {
            preparedExecution = prepareExecution()
        } else {
            runner { formatStandaloneOutput(prepareExecution().invoke()) }
        }
    }

    internal fun prepareForFlow(args: List<String>): suspend () -> String {
        preparedExecution = null
        preparingForFlow = true
        return try {
            context {
                expandArgumentFiles = false
                helpOptionNames = emptySet()
            }
            parse(args)
            checkNotNull(preparedExecution)
        } finally {
            preparingForFlow = false
        }
    }
}
