package dev.visiontest.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A single caller owns this connection. Nonblocking I/O gives every frame a finite deadline. */
internal class DaemonClientConnection(path: Path, private val controlTimeoutMillis: Long) : Closeable {
    private val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    private val selector = Selector.open()
    init {
        var connected = false
        try {
            validateSocket(path)
            channel.configureBlocking(false)
            channel.register(selector, 0)
            if (!channel.connect(UnixDomainSocketAddress.of(path))) {
                val deadline = deadline(controlTimeoutMillis)
                while (!channel.finishConnect()) await(SelectionKey.OP_CONNECT, deadline)
            }
            connected = true
        } finally {
            if (!connected) close()
        }
    }

    fun send(message: DaemonMessage) {
        val bytes = ByteArrayOutputStream().also {
            DaemonFrames.write(it, DaemonProtocol.encode(message))
        }.toByteArray()
        transfer(ByteBuffer.wrap(bytes), writing = true, deadline(controlTimeoutMillis))
    }

    fun receive(timeoutMillis: Long = controlTimeoutMillis): DaemonMessage {
        val deadline = deadline(timeoutMillis)
        val header = ByteBuffer.allocate(Int.SIZE_BYTES)
        transfer(header, writing = false, deadline)
        val length = header.flip().int
        if (length !in 1..DaemonFrames.MAX_FRAME_BYTES) throw IOException("Invalid daemon frame length: $length")
        val frame = ByteBuffer.allocate(Int.SIZE_BYTES + length).putInt(length)
        transfer(frame, writing = false, deadline)
        return DaemonProtocol.decode(requireNotNull(DaemonFrames.read(ByteArrayInputStream(frame.array()))))
    }

    private fun transfer(buffer: ByteBuffer, writing: Boolean, deadline: Long) {
        while (buffer.hasRemaining()) {
            checkIoState(deadline)
            val count = if (writing) channel.write(buffer) else channel.read(buffer)
            if (count < 0) throw EOFException("Daemon disconnected")
            if (count == 0) await(if (writing) SelectionKey.OP_WRITE else SelectionKey.OP_READ, deadline)
        }
    }

    private fun checkIoState(deadline: Long) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Daemon request interrupted")
        if (System.nanoTime() >= deadline) throw IOException("Timed out waiting for daemon")
    }

    private fun await(operation: Int, deadline: Long) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Daemon request interrupted")
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw IOException("Timed out waiting for daemon")
        channel.keyFor(selector).interestOps(operation)
        selector.select(maxOf(1, TimeUnit.NANOSECONDS.toMillis(remaining)))
        selector.selectedKeys().clear()
    }

    override fun close() {
        try { channel.close() } finally { selector.close() }
    }

    private fun validateSocket(path: Path) {
        val attributes = java.nio.file.Files.readAttributes(path,
            java.nio.file.attribute.PosixFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        val owner = path.fileSystem.userPrincipalLookupService
            .lookupPrincipalByName(System.getProperty("user.name"))
        val mode = java.nio.file.Files.getAttribute(path, "unix:mode", java.nio.file.LinkOption.NOFOLLOW_LINKS) as Int
        val permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
        val unsafeOwner = attributes.isSymbolicLink || attributes.owner() != owner
        val unsafeSocket = attributes.permissions() != permissions || mode and SOCKET_TYPE_MASK != SOCKET_TYPE
        if (unsafeOwner || unsafeSocket) {
            throw IOException("Unsafe daemon socket: requires current owner, Unix socket type and 0600 permissions")
        }
    }

    companion object {
        private const val SOCKET_TYPE_MASK = 0xF000
        private const val SOCKET_TYPE = 0xC000
    }

    private fun deadline(timeoutMillis: Long) = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
}
