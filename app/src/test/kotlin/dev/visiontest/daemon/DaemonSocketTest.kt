package dev.visiontest.daemon

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DaemonSocketTest {
    @Test
    @Suppress("NestedBlockDepth") // Both socket endpoints and the trickle thread need deterministic cleanup.
    fun `trickled hello cannot extend the frame deadline`() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "vts")
        val address = UnixDomainSocketAddress.of(directory.resolve("s"))
        val hello = DaemonMessage.Hello(DaemonIdentity(1, "dev", "a".repeat(64)))
        val frame = ByteArrayOutputStream().also { DaemonFrames.write(it, DaemonProtocol.encode(hello)) }.toByteArray()
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(address)
            SocketChannel.open(StandardProtocolFamily.UNIX).use { writer ->
                writer.connect(address)
                DaemonSocket(server.accept(), 100).use { reader ->
                    val trickle = Thread {
                        runCatching {
                            frame.forEach { byte ->
                                writer.write(ByteBuffer.wrap(byteArrayOf(byte)))
                                Thread.sleep(5)
                            }
                        }
                    }.apply { isDaemon = true; start() }
                    try {
                        assertFailsWith<IOException> { reader.receive() }
                    } finally {
                        writer.close()
                        trickle.join(1000)
                    }
                }
            }
        }
        directory.toFile().deleteRecursively()
    }
}
