package dev.visiontest.daemon

import dev.visiontest.cli.CliExecutionContext
import dev.visiontest.cli.CliRequestExecutor
import dev.visiontest.cli.CliResult
import dev.visiontest.config.VersionInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.CoroutineContext

/** Finite host ceiling leaves room for one-hour flows, observations and recording finalization. */
internal data class DaemonHostOptions(
    val queueLimit: Int = 32,
    val queueWaitMs: Long = 30_000,
    val handshakeMs: Long = 15_000,
    val ioMs: Long = 15_000,
    val requestMs: Long = TimeUnit.HOURS.toMillis(2),
    val idleMs: Long = TimeUnit.MINUTES.toMillis(IDLE_MINUTES),
    val drainMs: Long = 30_000,
    val cleanupMs: Long = 30_000,
)

@Suppress("TooManyFunctions") // Lifecycle and control entry points share one instance ownership boundary.
internal class DaemonHost(
    private val registry: DaemonRegistry = DaemonRegistry(),
    val identity: DaemonIdentity = DaemonIdentity(
        DaemonProtocol.VERSION, VersionInfo.version, DaemonBuildIdentity.load()),
    private val options: DaemonHostOptions = DaemonHostOptions(),
    private val resources: DaemonComponents = DaemonComponents(),
    private val now: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
    private val execution: ((DaemonMessage.Execute, CliExecutionContext, CoroutineContext) -> CliResult)? = null,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val connections = ConcurrentHashMap.newKeySet<DaemonSocket>()
    private val handlers = ThreadPoolExecutor(0, MAX_CONNECTIONS, HANDLER_IDLE_SECONDS, TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(), daemonThreadFactory("daemon-client"))
    private val worker = Executors.newSingleThreadExecutor(daemonThreadFactory("daemon-execute"))
    private val queue = ArrayBlockingQueue<Request>(options.queueLimit)
    @Volatile private var active: Request? = null
    @Volatile private var lastActivity = now()
    private var lease: DaemonRegistry.Lease? = null
    private var server: ServerSocketChannel? = null
    private var monitor: Thread? = null
    private val cleaned = AtomicBoolean(false)
    private val acquisitions = java.util.concurrent.atomic.AtomicLong()
    private val fenced = ConcurrentHashMap.newKeySet<String>()

    @Suppress("TooGenericExceptionCaught") // Release the registry lease on any failed startup.
    @Synchronized fun start() {
        check(!running.get() && lease == null) { "Daemon already started" }
        lease = registry.acquire() ?: throw IOException("Daemon already running")
        try {
            lease!!.removeStaleRegistration()
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
                bind(UnixDomainSocketAddress.of(registry.socketPath)); configureBlocking(false)
            }
            lease!!.publishReady(DaemonRegistration(ProcessHandle.current().pid(), identity))
            running.set(true)
            worker.submit { work() }
            monitor = daemonThreadFactory("daemon-watchdog").newThread { accept() }.also { it.start() }
        } catch (error: Exception) {
            cleanup(); throw error
        }
    }

    private fun accept() {
        while (running.get()) {
            try {
                acceptConnection()
                watchdog()
                Thread.sleep(POLL_MS)
            } catch (_: IOException) { if (running.get()) requestStop() }
        }
    }

    private fun acceptConnection() {
        val socket = server?.accept() ?: return
        val connection = DaemonSocket(socket, options.ioMs)
        connection.readTimeoutMs = options.handshakeMs
        connections.add(connection)
        try {
            handlers.execute { handle(connection) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            connections.remove(connection)
            connection.close()
        }
    }

    private fun watchdog() {
        val time = now()
        queue.toList().filter { time - it.accepted >= options.queueWaitMs }.forEach {
            if (queue.remove(it)) {
                it.fail("Daemon queue wait expired before execution")
                it.done.complete(Unit)
                it.connection.close()
            }
        }
        active?.let {
            if (time - it.started >= options.requestMs) { it.cancel(); it.connection.close() }
        }
        if (active == null && queue.isEmpty() && time - lastActivity >= options.idleMs) requestStop()
    }

    @Suppress("TooGenericExceptionCaught") // Invalid clients are isolated from the host.
    private fun handle(connection: DaemonSocket) {
        var request: Request? = null
        var failureRequestId = "daemon"
        try {
            val hello = connection.receive() as? DaemonMessage.Hello ?: throw IOException("Expected daemon hello")
            DaemonProtocol.requireCompatible(identity, hello.identity, false)
            connection.send(DaemonMessage.Ready(identity))
            connection.readTimeoutMs = options.handshakeMs
            when (val message = connection.receive()) {
                is DaemonMessage.Status -> connection.send(status(hello.identity))
                is DaemonMessage.Stop -> { connection.send(DaemonMessage.Stop(true)); requestStop() }
                is DaemonMessage.Execute -> {
                    failureRequestId = message.requestId
                    DaemonProtocol.requireCompatible(identity, hello.identity, true)
                    connection.readTimeoutMs = options.requestMs
                    request = Request(message, connection, now())
                    val admitted = synchronized(queue) { !stopping.get() && queue.offer(request) }
                    if (!admitted) request.fail("Daemon busy; request was not executed")
                    else {
                        lastActivity = now()
                        readControls(request)
                    }
                }
                else -> throw IOException("Expected daemon request")
            }
        } catch (error: Exception) {
            request?.cancel()
            if (request == null) runCatching {
                connection.send(DaemonMessage.Output(failureRequestId, "stderr", Base64.getEncoder()
                    .encodeToString("${error.message}\n".toByteArray())))
                connection.send(DaemonMessage.Completion(failureRequestId, 1))
            }
        } finally {
            request?.let { if (queue.remove(it)) it.done.complete(Unit) }
            connections.remove(connection)
            connection.close()
        }
    }

    private fun readControls(request: Request) {
        while (!request.done.isDone) {
            when (val message = request.connection.receive()) {
                is DaemonMessage.Cancel -> {
                    require(message.requestId == request.message.requestId)
                    request.cancel()
                    if (queue.remove(request)) request.done.complete(Unit)
                }
                is DaemonMessage.Input -> request.input(message)
                null -> { request.cancel(); return }
                else -> throw IOException("Unexpected daemon request control")
            }
        }
    }

    private fun work() {
        try {
            while (!stopping.get() || queue.isNotEmpty()) {
                val request = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                process(request)
            }
        } finally { if (stopping.get()) cleanup() }
    }

    private fun process(request: Request) {
        active = request
        request.started = now()
        try {
            if (!request.job.isActive) return
            if (now() - request.accepted >= options.queueWaitMs) {
                request.fail("Daemon queue wait expired before execution")
                return
            }
            execute(request)
        } finally {
            request.done.complete(Unit)
            request.connection.close()
            active = null
            lastActivity = now()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Operation failures become per-request diagnostics.
    private fun execute(request: Request) {
        val message = request.message
        val context = CliExecutionContext(Path.of(message.cwd), message.environment,
            readStdin = { request.readInput() }, loggingProperties = message.loggingProperties,
            terminalDimensions = message.terminalDimensions)
        val evidence = dev.visiontest.common.DispatchEvidence { platform ->
            if (platform in fenced) throw IOException(
                "Native outcome uncertain; restart native backend and daemon before mutations")
        }
        try {
            val result = execution?.invoke(message, context, request.job + evidence)
                ?: CliRequestExecutor(retainOutput = DaemonOutputBudget()::accept).execute(
                    message.arguments.toTypedArray(), context,
                    executionContext = request.job + evidence,
                    acquireComponents = { trace ->
                        evidence.platform = trace.platform ?: "unknown"
                        // Host subprocess mutation paths do not pass through HTTP evidence.
                        resources.acquire(context, trace).also {
                            acquisitions.incrementAndGet()
                            if (trace.operation in HOST_MUTATION_OPERATIONS) evidence.submittingMutation()
                        }
                    })
            request.result(result)
        } catch (error: Exception) {
            request.fail("${error.message ?: "Daemon execution cancelled"}; do not replay")
        } finally {
            if (evidence.uncertain || (!request.job.isActive && evidence.pending)) fenced.add(evidence.platform)
            if (evidence.transportFailed) resources.invalidate()
        }
    }

    private fun status(remote: DaemonIdentity) = DaemonMessage.Status(buildJsonObject {
        put("running", running.get() && !stopping.get()); put("pid", ProcessHandle.current().pid())
        put("compatible", remote == identity); put("initializationCount", resources.initializationCount)
        put("reuseCount", (acquisitions.get() - resources.initializationCount).coerceAtLeast(0))
        put("active", active != null); put("queued", queue.size); put("fenced", fenced.isNotEmpty())
    })

    private fun requestStop() {
        if (synchronized(queue) { stopping.compareAndSet(false, true) }) {
            daemonThreadFactory("daemon-stop").newThread { close() }.start()
        }
    }

    fun awaitStopped() { worker.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) }

    override fun close() {
        synchronized(queue) { stopping.set(true) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.drainMs)
        while ((active != null || queue.isNotEmpty()) && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        queue.toList().forEach { it.cancel(); if (queue.remove(it)) it.done.complete(Unit) }
        active?.cancel()
        running.set(false)
        server?.close()
        connections.forEach { it.close() }
        handlers.shutdownNow()
        worker.shutdown()
        worker.awaitTermination(options.cleanupMs, TimeUnit.MILLISECONDS)
        // A non-cooperative operation keeps ownership until its finally block actually settles.
        if (worker.isTerminated) cleanup()
    }

    @Synchronized private fun cleanup() {
        if (!cleaned.compareAndSet(false, true)) return
        running.set(false)
        try { resources.close() } finally {
            try { server?.close() } finally { lease?.close() }
        }
    }

    private inner class Request(val message: DaemonMessage.Execute, val connection: DaemonSocket, val accepted: Long) {
        val job = Job()
        val done = CompletableFuture<Unit>()
        @Volatile var started = Long.MAX_VALUE
        private val stdin = CompletableFuture<String>()
        private val bytes = ByteArrayOutputStream()
        private val wanted = AtomicBoolean(false)
        fun cancel() { job.cancel(); stdin.completeExceptionally(IOException("Daemon input cancelled")) }
        fun readInput(): String {
            if (wanted.compareAndSet(false, true)) connection.send(DaemonMessage.StdinRequest(message.requestId))
            return stdin.get(options.requestMs, TimeUnit.MILLISECONDS)
        }
        fun input(input: DaemonMessage.Input) {
            require(wanted.get() && input.requestId == message.requestId && !stdin.isDone)
            val chunk = Base64.getDecoder().decode(input.data)
            if (bytes.size().toLong() + chunk.size > DaemonFrames.MAX_FRAME_BYTES) {
                throw IOException("Daemon stdin limit exceeded")
            }
            bytes.write(chunk)
            if (input.eof) stdin.complete(bytes.toString(Charsets.UTF_8))
        }
        fun fail(text: String) { runCatching { result(CliResult(1, null, text)) } }
        fun result(result: CliResult) {
            val stdout = result.stdout?.let { "$it\n".toByteArray() }
            val stderr = result.stderr?.let { "$it\n".toByteArray() }
            val budget = DaemonOutputBudget()
            stdout?.let { budget.accept(it.size) }; stderr?.let { budget.accept(it.size) }
            listOf("stdout" to stdout, "stderr" to stderr).forEach { (stream, content) ->
                content?.let { DaemonProtocol.outputChunks(message.requestId, stream, it).forEach(connection::send) }
            }
            connection.send(DaemonMessage.Completion(message.requestId, result.exitCode))
        }
    }
}

private const val IDLE_MINUTES = 15L
private const val MAX_CONNECTIONS = 64
private const val HANDLER_IDLE_SECONDS = 30L
private const val POLL_MS = 5L

private val HOST_MUTATION_OPERATIONS = setOf("launch_app", "install_automation_server",
    "start_automation_server", "stop_automation_server")

private fun daemonThreadFactory(name: String) = java.util.concurrent.ThreadFactory { work ->
    Thread(work, name).apply { isDaemon = true }
}
