package dev.visiontest.daemon

import dev.visiontest.cli.CliResult
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("NestedBlockDepth") // Real socket lifetimes are scoped to deterministic cleanup.
class DaemonHostQueueRaceTest {
    private val identity = DaemonIdentity(1, "dev", "a".repeat(64))

    @Test
    fun `watchdog remains responsive when worker drains its single queued request`() = exerciseRace(false)

    @Test
    fun `shutdown completes when worker drains its single queued request`() = exerciseRace(true)

    private fun exerciseRace(shutdown: Boolean) {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "vtqr")
        val registry = DaemonRegistry(directory)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queue = DrainingQueue(if (shutdown) "close" else "watchdog", release)
        val host = DaemonHost(registry, identity, DaemonHostOptions(drainMs = 0), execution = { request, _, _ ->
            if (request.requestId == "first") {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            CliResult(0, null, null)
        })
        DaemonHost::class.java.getDeclaredField("queue").apply { isAccessible = true }.set(host, queue)
        try {
            host.start()
            connect(registry).use { first ->
                first.send(request(directory, "first"))
                assertTrue(started.await(5, TimeUnit.SECONDS))
                connect(registry).use { second ->
                    second.send(request(directory, "second"))
                    assertTrue(queue.admitted.await(5, TimeUnit.SECONDS))
                    queue.armed.set(true)
                    if (shutdown) host.close()
                    assertTrue(queue.traversed.await(5, TimeUnit.SECONDS), "Host must traverse the draining queue")
                    assertTrue(queue.drained.await(5, TimeUnit.SECONDS), "Worker must remove the queued request")
                    if (!shutdown) {
                        assertEquals(DaemonMessage.Completion("first", 0), first.receive())
                        assertEquals(DaemonMessage.Completion("second", 0), second.receive())
                        connect(registry).use { status ->
                            status.send(DaemonMessage.Status())
                            val details = (status.receive() as DaemonMessage.Status).details!!
                            assertEquals("true", details["running"].toString())
                        }
                        connect(registry).use { next ->
                            next.send(request(directory, "next"))
                            assertEquals(DaemonMessage.Completion("next", 0), next.receive())
                        }
                    }
                }
            }
        } finally {
            release.countDown()
            host.close()
            assertNull(registry.readMetadata())
            directory.toFile().deleteRecursively()
        }
    }

    private fun request(path: Path, id: String) = DaemonMessage.Execute(id, listOf("--version"),
        path.toString(), emptyMap(), emptyMap())

    private fun connect(registry: DaemonRegistry): DaemonSocket {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(registry.socketPath))
        return DaemonSocket(channel, 3000).also {
            it.send(DaemonMessage.Hello(identity))
            assertEquals(DaemonMessage.Ready(identity), it.receive())
        }
    }

    private class DrainingQueue(private val traversal: String, private val release: CountDownLatch) :
        ArrayBlockingQueue<Any>(32) {
        val admitted = CountDownLatch(2)
        val traversed = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val armed = AtomicBoolean()
        private val racing = AtomicBoolean()

        override fun offer(element: Any): Boolean = super.offer(element).also { if (it) admitted.countDown() }

        override fun poll(timeout: Long, unit: TimeUnit): Any? = super.poll(timeout, unit).also {
            if (it != null && racing.get()) drained.countDown()
        }

        override fun iterator(): MutableIterator<Any> {
            if (Thread.currentThread().stackTrace.any {
                it.className == DaemonHost::class.java.name && it.methodName == traversal
            } && armed.compareAndSet(true, false)) {
                // toList has already observed size one; release the worker before creating its iterator.
                racing.set(true)
                release.countDown()
                check(drained.await(5, TimeUnit.SECONDS))
                traversed.countDown()
            }
            return super.iterator()
        }
    }
}
