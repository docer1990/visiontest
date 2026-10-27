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

private fun startCallerAdb() {
    val process = HostProcessContext.builder(listOf("adb", "-P", "5037", "start-server"))
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
