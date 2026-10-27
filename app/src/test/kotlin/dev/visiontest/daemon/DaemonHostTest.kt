package dev.visiontest.daemon

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Suppress("NestedBlockDepth") // Real socket lifetimes are scoped to deterministic cleanup.
class DaemonHostTest {
    @Test
    fun `host publishes after bind and serves real executor version without native resources`() {
        val directory = Files.createTempDirectory(java.nio.file.Path.of("/tmp"), "vth")
        val registry = DaemonRegistry(directory)
        val identity = DaemonIdentity(1, "dev", "a".repeat(64))
        DaemonHost(registry, identity).use { host ->
            host.start()
            assertNotNull(registry.readMetadata())
            assertNull(registry.acquire())
            SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
                socket.connect(UnixDomainSocketAddress.of(registry.socketPath))
                val connection = DaemonSocket(socket, 5000)
                connection.send(DaemonMessage.Hello(identity))
                assertEquals(DaemonMessage.Ready(identity), connection.receive())
                connection.send(DaemonMessage.Execute("one", listOf("--version"), directory.toString(),
                    emptyMap(), emptyMap()))
                val output = connection.receive() as DaemonMessage.Output
                assertEquals("visiontest version dev\n",
                    java.util.Base64.getDecoder().decode(output.data).toString(Charsets.UTF_8))
                assertEquals(DaemonMessage.Completion("one", 0), connection.receive())
            }
            listOf(listOf("get_device_info") to 2, listOf("--help") to 0).forEach { (arguments, exitCode) ->
                SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
                    socket.connect(UnixDomainSocketAddress.of(registry.socketPath))
                    val connection = DaemonSocket(socket, 5000)
                    connection.send(DaemonMessage.Hello(identity)); connection.receive()
                    connection.send(DaemonMessage.Execute("parse", arguments, directory.toString(),
                        emptyMap(), emptyMap()))
                    val response = connection.receive() as DaemonMessage.Output
                    assertEquals(if (exitCode == 0) "stdout" else "stderr", response.stream)
                    assertEquals(DaemonMessage.Completion("parse", exitCode), connection.receive())
                }
            }
        }
        assertNull(registry.readMetadata())
        directory.toFile().deleteRecursively()
    }
    @Test
    fun `queue expires before dispatch while active work prevents idle expiry`() {
        val directory = Files.createTempDirectory(java.nio.file.Path.of("/tmp"), "vtq")
        val registry = DaemonRegistry(directory)
        val identity = DaemonIdentity(1, "dev", "a".repeat(64))
        val time = java.util.concurrent.atomic.AtomicLong(0)
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        DaemonHost(registry, identity, DaemonHostOptions(queueLimit = 1, queueWaitMs = 100, idleMs = 50),
            now = time::get,
            execution = { _, _, _ -> calls.incrementAndGet(); started.countDown()
                release.await(3, java.util.concurrent.TimeUnit.SECONDS)
                dev.visiontest.cli.CliResult(0, "done", null)
            }).use { host ->
            host.start()
            fun connect(): DaemonSocket {
                val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                channel.connect(UnixDomainSocketAddress.of(registry.socketPath))
                return DaemonSocket(channel, 3000).also {
                    it.send(DaemonMessage.Hello(identity)); it.receive()
                }
            }
            connect().use { first ->
                first.send(DaemonMessage.Execute("first", emptyList(), directory.toString(), emptyMap(), emptyMap()))
                kotlin.test.assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
                connect().use { queued ->
                    queued.send(DaemonMessage.Execute("queued", emptyList(), directory.toString(),
                        emptyMap(), emptyMap()))
                    // Query until the server has admitted the queued frame, avoiding timing assumptions.
                    var count = 0
                    while (count == 0) connect().use { status ->
                        status.send(DaemonMessage.Status())
                        count = (status.receive() as DaemonMessage.Status).details!!["queued"].toString().toInt()
                    }
                    connect().use { rejected ->
                        rejected.send(DaemonMessage.Execute("rejected", emptyList(), directory.toString(),
                            emptyMap(), emptyMap()))
                        assertEquals("stderr", (rejected.receive() as DaemonMessage.Output).stream)
                        assertEquals(DaemonMessage.Completion("rejected", 1), rejected.receive())
                    }
                    time.set(200)
                    assertEquals("stderr", (queued.receive() as DaemonMessage.Output).stream)
                    assertEquals(DaemonMessage.Completion("queued", 1), queued.receive())
                    assertNotNull(registry.readMetadata())
                    assertEquals(1, calls.get())
                }
                release.countDown()
                first.receive(); first.receive()
            }
        }
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `build mismatch permits status and stop but never executes`() {
        val directory = Files.createTempDirectory(java.nio.file.Path.of("/tmp"), "vtm")
        val registry = DaemonRegistry(directory)
        val identity = DaemonIdentity(1, "dev", "a".repeat(64))
        val other = identity.copy(build = "b".repeat(64))
        val calls = java.util.concurrent.atomic.AtomicInteger()
        DaemonHost(registry, identity, execution = { _, _, _ ->
            calls.incrementAndGet(); dev.visiontest.cli.CliResult(0, null, null)
        }).use { host ->
            host.start()
            fun connect(): DaemonSocket {
                val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                channel.connect(UnixDomainSocketAddress.of(registry.socketPath))
                return DaemonSocket(channel, 3000).also {
                    it.send(DaemonMessage.Hello(other)); it.receive()
                }
            }
            connect().use { status ->
                status.send(DaemonMessage.Status())
                assertEquals("false", (status.receive() as DaemonMessage.Status).details!!["compatible"].toString())
            }
            connect().use { request ->
                request.send(DaemonMessage.Execute("no", emptyList(), directory.toString(), emptyMap(), emptyMap()))
                assertEquals("stderr", (request.receive() as DaemonMessage.Output).stream)
                assertEquals(DaemonMessage.Completion("no", 1), request.receive())
            }
            assertEquals(0, calls.get())
            connect().use { stop ->
                stop.send(DaemonMessage.Stop()); assertEquals(DaemonMessage.Stop(true), stop.receive())
            }
            host.awaitStopped()
        }
        assertNull(registry.readMetadata())
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `stop closes the retained graph exactly once`() {
        val directory = Files.createTempDirectory(java.nio.file.Path.of("/tmp"), "vto")
        val registry = DaemonRegistry(directory)
        val identity = DaemonIdentity(1, "dev", "a".repeat(64))
        val holder = io.mockk.mockk<dev.visiontest.cli.ComponentHolder>(relaxed = true)
        val resources = DaemonComponents(factory = { holder })
        val host = DaemonHost(registry, identity, resources = resources, execution = { _, context, _ ->
            resources.acquire(context)
            dev.visiontest.cli.CliResult(0, null, null)
        })
        host.use {
            host.start()
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(registry.socketPath))
                val socket = DaemonSocket(channel, 3000)
                socket.send(DaemonMessage.Hello(identity)); socket.receive()
                socket.send(DaemonMessage.Execute("owned", emptyList(), directory.toString(), emptyMap(), emptyMap()))
                assertEquals(DaemonMessage.Completion("owned", 0), socket.receive())
            }
        }
        host.close()
        io.mockk.verify(exactly = 1) { holder.close() }
        assertNull(registry.readMetadata())
        directory.toFile().deleteRecursively()
    }

}
