package dev.visiontest.daemon

import java.io.IOException
import dev.visiontest.cli.CliExecutionContext
import dev.visiontest.config.VersionInfo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun runDaemonClient(arguments: List<String>, lifecycle: Boolean): Int = try {
    val client = DaemonClient(DaemonRegistry(),
        DaemonIdentity(DaemonProtocol.VERSION, VersionInfo.version, DaemonBuildIdentity.load()),
        stdin = System.`in`)
    if (lifecycle) runLifecycle(client, arguments) else
        client.execute(arguments, CliExecutionContext.local().let {
            it.copy(terminalDimensions = callerTerminalDimensions(it.environment))
        }, System.out, System.err)
} catch (error: IOException) {
    daemonFailure(error)
} catch (error: IllegalArgumentException) {
    daemonFailure(error)
} catch (error: InterruptedException) {
    Thread.currentThread().interrupt()
    daemonFailure(error)
}

private fun daemonFailure(error: Exception): Int {
    System.err.println("Daemon communication failed: ${error.message}")
    return 1
}

private fun runLifecycle(client: DaemonClient, arguments: List<String>): Int {
    when (arguments.drop(1)) {
        listOf("start") -> System.out.println("Daemon running (PID ${client.start().pid}).")
        listOf("status"), listOf("status", "--json") -> {
            val status = client.status()
            if (arguments.last() == "--json") {
                System.out.println(status ?: buildJsonObject { put("running", false) })
            } else {
                System.out.println(if (status == null) "Daemon stopped." else
                    "Daemon running (PID ${status["pid"]}, compatible=${status["compatible"]}).")
            }
        }
        listOf("stop") -> System.out.println(if (client.stop()) "Daemon stopping." else "Daemon stopped.")
        listOf("--help"), listOf("-h"), emptyList<String>() ->
            System.out.println("Usage: visiontest daemon {start|status [--json]|stop}")
        else -> {
            System.err.println("Usage: visiontest daemon {start|status [--json]|stop}")
            return 2
        }
    }
    return 0
}

internal fun runDaemonHost(arguments: List<String>) {
    require(arguments.isEmpty()) { "Internal daemon host accepts no arguments" }
    detachDaemonSession()
    DaemonHost().use { host ->
        val shutdown = Thread({ host.close() }, "visiontest-daemon-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            host.start()
            host.awaitStopped()
        } finally {
            Runtime.getRuntime().removeShutdownHook(shutdown)
        }
    }
}

/** The bundled JNA runtime is loaded only in the host, before it accepts any requests. */
private fun detachDaemonSession() {
    val nativeLibrary = Class.forName("com.sun.jna.NativeLibrary")
    val library = nativeLibrary.getMethod("getInstance", String::class.java).invoke(null, "c")
    val invokeInt = Class.forName("com.sun.jna.Function").getMethod("invokeInt", Array<Any>::class.java)
    fun call(name: String, vararg arguments: Any): Int {
        val function = nativeLibrary.getMethod("getFunction", String::class.java).invoke(library, name)
        return invokeInt.invoke(function, arguments as Any) as Int
    }
    // A directly launched host may already lead its session, where setsid would fail harmlessly.
    if (call("getsid", 0) != ProcessHandle.current().pid().toInt()) {
        check(call("setsid") >= 0) { "Cannot detach daemon from caller session" }
    }
}
