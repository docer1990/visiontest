package dev.visiontest.daemon

import dev.visiontest.cli.CliExecutionContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject

internal data class DaemonClientOptions(
    val controlTimeoutMillis: Long = 15_000,
    val executionTimeoutMillis: Long = TimeUnit.HOURS.toMillis(2),
    val stdinTimeoutMillis: Long = TimeUnit.HOURS.toMillis(1),
)

/** Thin protocol consumer: no command tree, terminal framework, or device construction. */
internal class DaemonClient(
    private val registry: DaemonRegistry,
    private val identity: DaemonIdentity,
    private val launcher: DaemonLauncher = DaemonLauncher(registry),
    private val options: DaemonClientOptions = DaemonClientOptions(),
    private val stdin: InputStream? = null,
) {
    fun start(): DaemonRegistration {
        val registration = launcher.ensureRunning()
        connect(execution = true).use { }
        return registration
    }

    fun status(): JsonObject? {
        if (registry.readMetadata() == null) return null
        return connect(execution = false).use {
            it.send(DaemonMessage.Status())
            (it.receive() as? DaemonMessage.Status)?.details ?: throw IOException("Invalid daemon status response")
        }
    }

    fun stop(): Boolean {
        if (registry.readMetadata() == null) return false
        return connect(execution = false).use {
            it.send(DaemonMessage.Stop())
            (it.receive() as? DaemonMessage.Stop)?.accepted ?: throw IOException("Invalid daemon stop response")
        }
    }

    fun execute(
        arguments: List<String>, context: CliExecutionContext, stdout: OutputStream, stderr: OutputStream,
    ): Int {
        val requestId = UUID.randomUUID().toString()
        var connection: DaemonClientConnection? = null
        var submitted = false
        return try {
            launcher.ensureRunning()
            connection = connect(execution = true)
            // From this point a failed write may have dispatched work. Never retry or fall back.
            submitted = true
            connection.send(DaemonMessage.Execute(
                requestId, arguments, context.workingDirectory.toString(),
                context.environment, context.loggingProperties, context.terminalDimensions,
                context.routingProperties,
            ))
            receiveExecution(connection, requestId, context, stdout, stderr)
        } catch (error: InterruptedException) {
            Thread.interrupted()
            try {
                failure(connection, requestId, submitted, stderr, error)
            } finally { Thread.currentThread().interrupt() }
        } catch (error: IOException) {
            failure(connection, requestId, submitted, stderr, error)
        } finally {
            try { connection?.close() } catch (_: IOException) {
                // A close failure cannot invalidate an already received completion.
            }
        }
    }

    private fun failure(
        connection: DaemonClientConnection?, requestId: String, submitted: Boolean,
        stderr: OutputStream, error: Exception,
    ): Int {
        if (submitted) {
            try {
                connection?.send(DaemonMessage.Cancel(requestId))
            } catch (_: IOException) {
                // Best effort: disconnect also informs the host.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val outcome = if (submitted) {
            "; dispatch outcome is uncertain, do not replay; cancellation does not confirm native stop"
        } else ""
        stderr.write("Daemon communication failed: ${error.message}$outcome\n".toByteArray())
        stderr.flush()
        return 1
    }

    private fun connect(execution: Boolean): DaemonClientConnection {
        val connection = DaemonClientConnection(registry.socketPath, options.controlTimeoutMillis)
        var connected = false
        try {
            connection.send(DaemonMessage.Hello(identity))
            val remote = connection.receive() as? DaemonMessage.Ready ?: throw IOException("Expected daemon readiness")
            DaemonProtocol.requireCompatible(identity, remote.identity, execution)
            connected = true
            return connection
        } finally {
            if (!connected) connection.close()
        }
    }

    private fun receiveExecution(
        connection: DaemonClientConnection,
        requestId: String,
        context: CliExecutionContext,
        stdout: OutputStream,
        stderr: OutputStream,
    ): Int {
        val budget = DaemonOutputBudget()
        var stdinSent = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.executionTimeoutMillis)
        while (true) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) throw IOException("Daemon execution timed out")
            when (val message = connection.receive(remaining)) {
                is DaemonMessage.Output -> {
                    requireRequest(requestId, message.requestId)
                    val bytes = Base64.getDecoder().decode(message.data)
                    budget.accept(bytes.size)
                    val target = if (message.stream == "stdout") stdout else stderr
                    target.write(bytes)
                    target.flush()
                }
                is DaemonMessage.Completion -> {
                    requireRequest(requestId, message.requestId)
                    return message.exitCode
                }
                is DaemonMessage.StdinRequest -> {
                    requireRequest(requestId, message.requestId)
                    checkStdinRequest(stdinSent)
                    stdinSent = true
                    sendStdin(connection, requestId, context)
                }
                else -> throw IOException("Unexpected daemon execution response")
            }
        }
    }

    private fun checkStdinRequest(stdinSent: Boolean) {
        if (stdinSent) throw IOException("Repeated daemon stdin request")
    }

    private fun sendStdin(connection: DaemonClientConnection, requestId: String, context: CliExecutionContext) {
        if (!stdinReader.tryAcquire()) throw IOException("Previous stdin reader is still active")
        val task = FutureTask {
            val bytes = stdin?.readNBytes(DaemonFrames.MAX_FRAME_BYTES + 1)
                ?: context.readStdin().toByteArray(Charsets.UTF_8)
            boundedStdin(bytes)
        }
        Thread({
            try { task.run() } finally { stdinReader.release() }
        }, "visiontest-daemon-stdin").apply { isDaemon = true; start() }
        val bytes = awaitStdin(task, options.stdinTimeoutMillis)
        for (offset in bytes.indices step DaemonProtocol.MAX_CHUNK_BYTES) {
            val chunk = bytes.copyOfRange(offset, minOf(bytes.size, offset + DaemonProtocol.MAX_CHUNK_BYTES))
            connection.send(DaemonMessage.Input(requestId, Base64.getEncoder().encodeToString(chunk), false))
        }
        connection.send(DaemonMessage.Input(requestId, "", true))
    }

    companion object {
        // At most one blocked reader survives cancellation; future requests fail instead of leaking more readers.
        private val stdinReader = Semaphore(1)
    }
}

private fun boundedStdin(bytes: ByteArray): ByteArray {
    if (bytes.size > DaemonFrames.MAX_FRAME_BYTES) throw IOException("Daemon stdin exceeds 8 MiB")
    return bytes
}

private fun requireRequest(expected: String, actual: String) {
    if (actual != expected) throw IOException("Daemon response request identity mismatch")
}

private fun awaitStdin(task: FutureTask<ByteArray>, timeoutMillis: Long): ByteArray = try {
    task.get(timeoutMillis, TimeUnit.MILLISECONDS)
} catch (error: java.util.concurrent.ExecutionException) {
    throw IOException("Cannot read daemon stdin", error)
} catch (error: java.util.concurrent.TimeoutException) {
    throw IOException("Timed out reading daemon stdin", error)
} finally { task.cancel(true) }
