package dev.visiontest.daemon

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit

/** Independent nonblocking reads/writes avoid the shared blockingLock of Channels stream adapters. */
internal class DaemonSocket(private val channel: SocketChannel, private val timeoutMs: Long) : Closeable {
    var readTimeoutMs: Long = timeoutMs
    init { channel.configureBlocking(false) }

    private val input = object : InputStream() {
        override fun read(): Int {
            val bytes = ByteArray(1)
            return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and BYTE_MASK
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            transfer(ByteBuffer.wrap(bytes, offset, length), false)
    }
    private val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val buffer = ByteBuffer.wrap(bytes, offset, length)
            while (buffer.hasRemaining()) transfer(buffer, true)
        }
    }

    fun receive(): DaemonMessage? = DaemonFrames.read(input)?.let(DaemonProtocol::decode)
    @Synchronized fun send(message: DaemonMessage) = DaemonFrames.write(output, DaemonProtocol.encode(message))
    override fun close() = channel.close()

    private fun transfer(buffer: ByteBuffer, writing: Boolean): Int {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(if (writing) timeoutMs else readTimeoutMs)
        while (true) {
            val count = if (writing) channel.write(buffer) else channel.read(buffer)
            if (count != 0 || !buffer.hasRemaining()) return count
            if (System.nanoTime() >= deadline) throw IOException("Daemon socket I/O timed out; do not replay requests")
            Thread.sleep(IO_POLL_MS)
        }
    }
}

private const val BYTE_MASK = 255
private const val IO_POLL_MS = 5L
