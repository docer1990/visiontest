package dev.visiontest.daemon

import dev.visiontest.cli.CliExecutionContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonClientTest {
    private val identity = DaemonIdentity(1, "test", "a".repeat(64))

    @Test fun `two calls forward context and exact output without reading stdin`() = fixture(2, handler = {
        input, output, request ->
        assertEquals(listOf("tap", "--json"), request.arguments)
        assertEquals(mapOf("ONE" to "two"), request.environment)
        assertEquals(mapOf("org.slf4j.simpleLogger.defaultLogLevel" to "debug"), request.loggingProperties)
        respond(input, output, request, stdin = false, disconnect = false)
    }) { registry, server ->
        val client = DaemonClient(registry, identity)
        repeat(2) {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            assertEquals(5, client.execute(listOf("tap", "--json"), CliExecutionContext(
                environment = mapOf("ONE" to "two"), readStdin = { error("unexpected stdin") },
                loggingProperties = mapOf("org.slf4j.simpleLogger.defaultLogLevel" to "debug"),
            ), out, err))
            assertEquals("héllo\n", out.toString("UTF-8"))
            assertEquals("warning", err.toString("UTF-8"))
        }
        server.get(5, TimeUnit.SECONDS)
    }

    @Test fun `lost completion never retries submitted command`() = fixture(1, disconnect = true) { registry, server ->
        val err = ByteArrayOutputStream()
        assertEquals(1, DaemonClient(registry, identity).execute(
            listOf("tap"), CliExecutionContext(), ByteArrayOutputStream(), err,
        ))
        assertTrue(err.toString().contains("uncertain"))
        server.get(5, TimeUnit.SECONDS)
    }

    @Test fun `different build blocks execution but permits explicit stop`() = fixture(
        2, remote = identity.copy(build = "b".repeat(64)),
    ) { registry, server ->
        val client = DaemonClient(registry, identity)
        assertEquals(1, client.execute(
            listOf("tap"), CliExecutionContext(), ByteArrayOutputStream(), ByteArrayOutputStream(),
        ))
        assertTrue(client.stop())
        server.get(5, TimeUnit.SECONDS)
    }

    @Test fun `stdin is sent only on demand`() = fixture(1, stdin = true) { registry, server ->
        var read = false
        val context = CliExecutionContext(readStdin = { read = true; "input ✓" })
        assertFalse(read)
        assertEquals(5, DaemonClient(registry, identity).execute(
            listOf("flow"), context, ByteArrayOutputStream(), ByteArrayOutputStream(),
        ))
        assertTrue(read)
        server.get(5, TimeUnit.SECONDS)
    }

    @Test fun `unsafe live socket is rejected before handshake`() = fixture(0) { registry, _ ->
        Files.setPosixFilePermissions(registry.socketPath,
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw----"))
        val err = ByteArrayOutputStream()
        assertEquals(1, DaemonClient(registry, identity).execute(
            listOf("tap"), CliExecutionContext(), ByteArrayOutputStream(), err,
        ))
        assertTrue(err.toString().contains("Unsafe daemon socket"))
    }

    @Test fun `interruption sends cancel without replaying execution`() {
        val admitted = CompletableFuture<Void>()
        val canceled = CompletableFuture<DaemonMessage>()
        fixture(1, handler = { input, _, _ ->
            admitted.complete(null)
            canceled.complete(DaemonProtocol.decode(requireNotNull(DaemonFrames.read(input))))
        }) { registry, server ->
            val err = ByteArrayOutputStream()
            val result = CompletableFuture<Int>()
            val worker = Thread {
                result.complete(DaemonClient(registry, identity).execute(
                    listOf("tap"), CliExecutionContext(), ByteArrayOutputStream(), err,
                ))
            }
            worker.start()
            admitted.get(5, TimeUnit.SECONDS)
            worker.interrupt()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertEquals(1, result.get(1, TimeUnit.SECONDS))
            assertTrue(canceled.get(1, TimeUnit.SECONDS) is DaemonMessage.Cancel)
            assertTrue(err.toString().contains("does not confirm native stop"))
            server.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun `wrong request identity preserves earlier bytes and fails`() = fixture(
        1, handler = { _, output, request ->
        send(output, DaemonProtocol.outputChunks(request.requestId, "stdout", "known".toByteArray()).single())
        send(output, DaemonMessage.Completion("wrong", 0))
    }) { registry, server ->
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        assertEquals(1, DaemonClient(registry, identity).execute(listOf("tap"), CliExecutionContext(), out, err))
        assertEquals("known", out.toString())
        assertTrue(err.toString().contains("identity mismatch"))
        server.get(5, TimeUnit.SECONDS)
    }

    @Test fun `bounded raw stdin rejects over limit without allocating through context callback`() = fixture(
        1, handler = { input, output, request ->
            send(output, DaemonMessage.StdinRequest(request.requestId))
            assertTrue(DaemonProtocol.decode(requireNotNull(DaemonFrames.read(input))) is DaemonMessage.Cancel)
        },
    ) { registry, server ->
        val raw = object : InputStream() {
            var reads = 0
            override fun read(): Int = if (reads++ <= DaemonFrames.MAX_FRAME_BYTES) 'x'.code else -1
        }
        val err = ByteArrayOutputStream()
        val client = DaemonClient(registry, identity, stdin = raw)
        assertEquals(1, client.execute(listOf("flow"), CliExecutionContext(
            readStdin = { error("raw reader must be used") },
        ), ByteArrayOutputStream(), err))
        assertEquals(DaemonFrames.MAX_FRAME_BYTES + 1, raw.reads)
        assertTrue(err.toString().contains("Cannot read daemon stdin"))
        server.get(5, TimeUnit.SECONDS)
    }

    @Test fun `UTF8 split across output chunks remains exact bytes`() = fixture(1, handler = { _, output, request ->
        val bytes = "é✓".toByteArray()
        bytes.forEach { byte ->
            send(output, DaemonMessage.Output(request.requestId, "stdout", Base64.getEncoder()
                .encodeToString(byteArrayOf(byte))))
        }
        send(output, DaemonMessage.Completion(request.requestId, 0))
    }) { registry, server ->
        val out = ByteArrayOutputStream()
        assertEquals(0, DaemonClient(registry, identity).execute(
            listOf("tap"), CliExecutionContext(), out, ByteArrayOutputStream(),
        ))
        assertEquals("é✓", out.toString("UTF-8"))
        server.get(5, TimeUnit.SECONDS)
    }

    // Fixture parameters model independent wire behaviors without requiring a separate fake host class.
    @Suppress("LongParameterList")
    private fun fixture(
        count: Int,
        disconnect: Boolean = false,
        remote: DaemonIdentity = identity,
        stdin: Boolean = false,
        handler: ((InputStream, OutputStream, DaemonMessage.Execute) -> Unit)? = null,
        body: (DaemonRegistry, CompletableFuture<Void>) -> Unit,
    ) {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "vtc-")
        val registry = DaemonRegistry(directory)
        val lease = requireNotNull(registry.acquire())
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
            listener.bind(UnixDomainSocketAddress.of(registry.socketPath))
            lease.publishReady(DaemonRegistration(ProcessHandle.current().pid(), remote))
            val server = CompletableFuture.runAsync {
                repeat(count) {
                    listener.accept().use { channel ->
                        val input = Channels.newInputStream(channel)
                        val output = Channels.newOutputStream(channel)
                        val hello = DaemonProtocol.decode(requireNotNull(DaemonFrames.read(input)))
                        assertTrue(hello is DaemonMessage.Hello)
                        send(output, DaemonMessage.Ready(remote))
                        when (val request = DaemonFrames.read(input)?.let(DaemonProtocol::decode)) {
                            null -> Unit
                            is DaemonMessage.Stop -> send(output, DaemonMessage.Stop(true))
                            is DaemonMessage.Execute -> {
                                if (handler != null) handler(input, output, request)
                                else respond(input, output, request, stdin, disconnect)
                            }
                            else -> error("unexpected request: $request")
                        }
                    }
                }
            }
            try {
                body(registry, server)
                listener.configureBlocking(false)
                listener.accept()?.use { error("Unexpected replay or additional connection") }
            } finally { lease.close() }
        }
        Files.deleteIfExists(registry.lockPath)
        Files.delete(directory)
    }

    private fun respond(
        input: InputStream, output: OutputStream, request: DaemonMessage.Execute, stdin: Boolean, disconnect: Boolean,
    ) {
        assertTrue(Path.of(request.cwd).isAbsolute)
        if (stdin) {
            send(output, DaemonMessage.StdinRequest(request.requestId))
            val bytes = ByteArrayOutputStream()
            do {
                val chunk = DaemonProtocol.decode(requireNotNull(DaemonFrames.read(input))) as DaemonMessage.Input
                bytes.write(Base64.getDecoder().decode(chunk.data))
            } while (!chunk.eof)
            assertEquals("input ✓", bytes.toString("UTF-8"))
        }
        if (!disconnect) {
            DaemonProtocol.outputChunks(request.requestId, "stdout", "héllo\n".toByteArray()).forEach {
                send(output, it)
            }
            DaemonProtocol.outputChunks(request.requestId, "stderr", "warning".toByteArray()).forEach {
                send(output, it)
            }
            send(output, DaemonMessage.Completion(request.requestId, 5))
        }
    }

    private fun send(output: OutputStream, message: DaemonMessage) =
        DaemonFrames.write(output, DaemonProtocol.encode(message))
}
