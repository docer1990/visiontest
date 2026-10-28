package dev.visiontest.android

import com.malinskiy.adam.interactor.StartAdbInteractor
import dev.visiontest.cli.CliExecutionContext
import dev.visiontest.common.HostProcessContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class AndroidHostProcesses(
    val startAdb: suspend () -> Unit = {
        if (CliExecutionContext.currentOrNull() == null) StartAdbInteractor().execute() else startCallerAdb()
    },
    val launchAdb: (List<String>) -> Process = { command ->
        HostProcessContext.builder(command).redirectErrorStream(true).start()
    },
)

internal fun callerAdbExecutable(): String {
    val context = CliExecutionContext.current()
    val sdk = context.environmentValue("ANDROID_HOME") ?: context.environmentValue("ANDROID_SDK_ROOT")
    return sdk?.let { context.resolve(it).resolve("platform-tools/adb").toString() } ?: "adb"
}

private fun startCallerAdb() {
    val adb = callerAdbExecutable()
    val context = CliExecutionContext.current()
    val port = context.routingProperties["ANDROID_ADB_SERVER_PORT"]
        ?: context.environmentValue("ANDROID_ADB_SERVER_PORT") ?: "5037"
    val process = HostProcessContext.builder(listOf(adb, "-P", port, "start-server"))
        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    try {
        if (!process.waitFor(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw TimeoutException("ADB startup timed out")
        check(process.exitValue() == 0) { "ADB startup failed with exit code ${process.exitValue()}" }
    } finally {
        if (process.isAlive) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
    }
}

private const val START_TIMEOUT_SECONDS = 30L
