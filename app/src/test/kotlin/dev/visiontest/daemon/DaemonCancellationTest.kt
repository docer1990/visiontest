package dev.visiontest.daemon

import dev.visiontest.cli.CliResult
import dev.visiontest.common.DispatchEvidence
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Suppress("NestedBlockDepth") // Real socket lifetimes are scoped to deterministic cleanup.
class DaemonCancellationTest {
    private val identity = DaemonIdentity(1, "dev", "a".repeat(64))

    @Test
    fun `cancellation retains serial boundary until cleanup and fences uncertain mutation`() =
        exerciseCancellation(false)

    @Test
    fun `disconnect cancels once and fences mutation after cleanup`() = exerciseCancellation(true)

    private fun exerciseCancellation(disconnect: Boolean) {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "vtc")
        val registry = DaemonRegistry(directory)
        val started = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        DaemonHost(registry, identity, execution = { message, _, context ->
            calls.incrementAndGet()
            val evidence = context[DispatchEvidence]!!
            evidence.platform = "android"
            evidence.submittingMutation()
            if (message.requestId == "one") runBlocking(context) {
                started.countDown()
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleanup.countDown(); release.await(3, TimeUnit.SECONDS) }
                }
            }
            CliResult(0, "ok", null)
        }).use { host ->
            host.start()
            connect(registry).use { first ->
                first.send(request(directory, "one"))
                assertTrue(started.await(3, TimeUnit.SECONDS))
                connect(registry).use { second ->
                    second.send(request(directory, "two"))
                    if (disconnect) first.close() else first.send(DaemonMessage.Cancel("one"))
                    assertTrue(cleanup.await(3, TimeUnit.SECONDS))
                    assertEquals(1, calls.get())
                    connect(registry).use { status ->
                        status.send(DaemonMessage.Status())
                        assertTrue(status.receive() is DaemonMessage.Status)
                    }
                    release.countDown()
                    val result = second.receive() as DaemonMessage.Output
                    assertEquals("stderr", result.stream)
                    assertTrue(java.util.Base64.getDecoder().decode(result.data).toString(Charsets.UTF_8)
                        .contains("uncertain"))
                    assertEquals(DaemonMessage.Completion("two", 1), second.receive())
                }
            }
        }
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `stdin is requested lazily and read through input frames`() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "vti")
        val registry = DaemonRegistry(directory)
        DaemonHost(registry, identity, execution = { _, context, _ -> CliResult(0, context.readStdin(),
            null) }).use { host ->
            host.start()
            connect(registry).use { socket ->
                socket.send(request(directory, "input"))
                assertEquals(DaemonMessage.StdinRequest("input"), socket.receive())
                socket.send(DaemonMessage.Input("input", "aGVsbG8=", true))
                val output = socket.receive() as DaemonMessage.Output
                assertEquals("hello\n", java.util.Base64.getDecoder().decode(output.data).toString(Charsets.UTF_8))
                assertEquals(DaemonMessage.Completion("input", 0), socket.receive())
            }
        }
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `flow HTTP rejection fences next mutation and invalidates settled graph`() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "vtf")
        val registry = DaemonRegistry(directory)
        val holder = io.mockk.mockk<dev.visiontest.cli.ComponentHolder>(relaxed = true)
        val resources = DaemonComponents(factory = { holder })
        okhttp3.mockwebserver.MockWebServer().use { backend ->
            backend.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(500).setBody("lost outcome"))
            backend.enqueue(okhttp3.mockwebserver.MockResponse().setBody("confirmed"))
            backend.start()
            val client = dev.visiontest.android.AutomationClient("localhost", backend.port)
            DaemonHost(registry, identity, resources = resources, execution = { message, context, executionContext ->
                executionContext[DispatchEvidence]!!.platform = "android"
                if (message.requestId == "rejected") resources.acquire(context)
                dev.visiontest.cli.executeCliResultCommand(executionContext +
                    dev.visiontest.common.RequestDeadline(5000)) {
                    CliResult(0, client.sendRequest("ui.tapByCoordinates"), null)
                }
            }).use { host ->
                host.start()
                connect(registry).use { first ->
                    first.send(request(directory, "rejected"))
                    assertEquals("stderr", (first.receive() as DaemonMessage.Output).stream)
                    assertEquals(DaemonMessage.Completion("rejected", 1), first.receive())
                }
                connect(registry).use { next ->
                    next.send(request(directory, "fenced"))
                    val output = next.receive() as DaemonMessage.Output
                    assertEquals("stderr", output.stream)
                    assertTrue(java.util.Base64.getDecoder().decode(output.data).toString(Charsets.UTF_8)
                        .contains("uncertain"))
                    assertEquals(DaemonMessage.Completion("fenced", 1), next.receive())
                }
                assertEquals(1, backend.requestCount)
                io.mockk.verify(exactly = 1) { holder.close() }
            }
        }
        directory.toFile().deleteRecursively()
    }

    private fun request(path: Path, id: String) = DaemonMessage.Execute(id, listOf("--version"),
        path.toString(), emptyMap(), emptyMap())
    private fun connect(registry: DaemonRegistry): DaemonSocket {
        val socket = SocketChannel.open(StandardProtocolFamily.UNIX)
        socket.connect(UnixDomainSocketAddress.of(registry.socketPath))
        return DaemonSocket(socket, 5000).also {
            it.send(DaemonMessage.Hello(identity)); assertEquals(DaemonMessage.Ready(identity), it.receive())
        }
    }
}
