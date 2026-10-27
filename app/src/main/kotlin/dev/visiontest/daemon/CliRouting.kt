package dev.visiontest.daemon

internal enum class CliRoute { MCP, HOST, LOCAL, STANDALONE, LIFECYCLE, DAEMON }
internal data class CliRouting(val route: CliRoute, val arguments: List<String>)

/** Inspect only root options. Command data and argument files belong to the authoritative parser. */
internal fun classifyCliArguments(args: Array<String>): CliRouting = when {
    args.isEmpty() || args.first() == "serve" -> CliRouting(CliRoute.MCP, args.toList())
    args.first() == "--internal-daemon-host" -> CliRouting(CliRoute.HOST, args.drop(1))
    else -> RootArgumentScan(args.toList()).classify()
}

private class RootArgumentScan(private val arguments: List<String>) {
    private var standalone = false
    private var local = false
    private var command: String? = null
    private val forwarded = mutableListOf<String>()

    fun classify(): CliRouting {
        var index = 0
        while (index < arguments.size && command == null) {
            val argument = arguments[index]
            when {
                argument == "--" -> {
                    command = "--"
                    forwarded.addAll(arguments.drop(index))
                }
                argument == "--standalone" -> standalone = true
                argument in listOf("--help", "-h", "--version") -> {
                    local = true
                    forwarded.add(argument)
                }
                argument == "--trace-performance" -> {
                    forwarded.add(argument)
                    index++
                    arguments.getOrNull(index)?.let(forwarded::add)
                }
                !argument.startsWith("-") -> {
                    command = argument
                    forwarded.addAll(arguments.drop(index))
                }
                else -> forwarded.add(argument)
            }
            index++
        }
        return CliRouting(selectedRoute(), forwarded)
    }

    private fun selectedRoute(): CliRoute = when {
        local || command == "init" -> CliRoute.LOCAL
        standalone -> CliRoute.STANDALONE
        command == "daemon" -> CliRoute.LIFECYCLE
        else -> CliRoute.DAEMON
    }
}
