package com.example.visiontest.performance

import java.io.Writer
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class TraceCloseResult(val complete: Boolean, val written: Long, val dropped: Long)

internal class JsonlTraceSink(
    private val writer: Writer,
    private val diagnostic: (String) -> Unit,
    capacity: Int = 4096,
) {
    private val queue = ArrayBlockingQueue<String>(capacity)
    private val state = Any()
    private val warned = AtomicBoolean()
    private val done = CountDownLatch(1)
    private var closing = false
    private var failed = false
    private var expired = false
    private var pending = 0L
    private var deadlineNs: Long? = null
    private var written = 0L
    private var dropped = 0L
    private var terminal: ((TraceCloseResult) -> String)? = null
    private var result: TraceCloseResult? = null
    private val worker = Thread(::drain, "visiontest-trace-writer").apply { isDaemon = true; start() }

    fun offer(line: String): Boolean {
        val accepted = synchronized(state) {
            if (closing || failed) return false
            queue.offer(line).also { if (it) pending++ else dropped++ }
        }
        if (!accepted) warn()
        return accepted
    }

    fun finish(timeoutMs: Long): TraceCloseResult = finish(timeoutMs, null)

    fun finish(timeoutMs: Long, terminalRecord: ((TraceCloseResult) -> String)?): TraceCloseResult {
        synchronized(state) {
            result?.let { return it }
            if (!closing) {
                deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    timeoutMs.coerceIn(0, MAX_TIMEOUT_MS),
                )
                terminal = terminalRecord
                closing = true
            }
        }
        val drained = try {
            done.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val snapshot = synchronized(state) {
            result ?: run {
                if (!drained) expire()
                TraceCloseResult(drained && !failed && !expired && dropped == 0L, written, dropped)
                    .also { result = it }
            }
        }
        if (!snapshot.complete) warn()
        if (!drained) worker.interrupt()
        return snapshot
    }

    private fun drain() {
        try {
            drainQueue()
            writeTerminal()
        } catch (_: Exception) {
            synchronized(state) { failed = true; dropped += pending; pending = 0; queue.clear() }
            warn()
        } finally {
            try {
                writer.close()
            } catch (_: Exception) {
                synchronized(state) { failed = true }
                warn()
            }
            done.countDown()
        }
    }

    private fun writeTerminal() {
        if (canWrite()) {
            writer.flush()
            val snapshot = synchronized(state) { TraceCloseResult(dropped == 0L, written, dropped) }
            val line = terminal?.invoke(snapshot)
            if (line != null && canWrite()) writer.write(line + "\n")
            if (canWrite()) writer.flush()
        }
    }

    private fun drainQueue() {
        while (canWrite()) {
            val line = queue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
            if (line == null) {
                if (synchronized(state) { closing }) break
            } else if (canWrite()) {
                writer.write(line + "\n")
                synchronized(state) {
                    if (!expired) { written++; pending-- }
                }
            }
        }
    }

    private fun canWrite(): Boolean = synchronized(state) {
        if (deadlineNs?.let { System.nanoTime() - it >= 0 } == true) expire()
        !expired
    }

    private fun expire() {
        if (!expired) {
            expired = true
            dropped += pending
            pending = 0
            queue.clear()
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 10L
        private const val MAX_TIMEOUT_MS = Long.MAX_VALUE / 2 / 1_000_000
        private val ownedFiles = mutableSetOf<Any>()
        private const val DIAGNOSTIC = "VisionTest performance trace is unavailable or incomplete."

        @Synchronized
        fun open(path: Path, diagnostic: (String) -> Unit, capacity: Int = 4096): JsonlTraceSink? {
            var channel: FileChannel? = null
            var reader: FileChannel? = null
            var key: Any? = null
            return try {
                require(capacity > 0)
                val target = path.toAbsolutePath().normalize()
                Files.createDirectories(target.parent)
                if (Files.exists(target, NOFOLLOW_LINKS)) {
                    require(fileIdentity(target) !in ownedFiles)
                }
                channel = FileChannel.open(target, WRITE, CREATE, APPEND, NOFOLLOW_LINKS)
                key = fileIdentity(target)
                val lock = channel.tryLock() ?: error("Trace already owned")
                reader = FileChannel.open(target, READ, NOFOLLOW_LINKS)
                val writer = FileTraceWriter(channel, reader, lock, key, needsNewline(reader))
                ownedFiles.add(key)
                JsonlTraceSink(writer, diagnostic, capacity)
            } catch (_: Exception) {
                try { channel?.close() } catch (_: Exception) { /* Cleanup is best effort. */ }
                try { reader?.close() } catch (_: Exception) { /* Cleanup is best effort. */ }
                key?.let { ownedFiles.remove(it) }
                report(diagnostic)
                null
            }
        }

        private class FileTraceWriter(
            channel: FileChannel,
            private val reader: FileChannel,
            private val fileLock: FileLock,
            private val key: Any,
            private val needsNewline: Boolean,
        ) : Writer() {
            private val delegate = Channels.newWriter(channel, StandardCharsets.UTF_8)
            private var firstWrite = true

            override fun write(buffer: CharArray, offset: Int, count: Int) {
                if (firstWrite) {
                    firstWrite = false
                    if (needsNewline) delegate.write("\n")
                }
                delegate.write(buffer, offset, count)
            }

            override fun flush() = delegate.flush()

            override fun close() {
                try {
                    delegate.close()
                } finally {
                    releaseOwnership()
                }
            }

            private fun releaseOwnership() {
                try {
                    reader.close()
                    if (fileLock.isValid) fileLock.release()
                } finally {
                    synchronized(Companion) { ownedFiles.remove(key) }
                }
            }
        }

        private fun fileIdentity(path: Path): Any {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attributes.isRegularFile)
            return attributes.fileKey() ?: path.toRealPath(NOFOLLOW_LINKS)
        }

        private fun needsNewline(channel: FileChannel): Boolean {
            val size = channel.size()
            if (size == 0L) return false
            val last = ByteBuffer.allocate(1)
            channel.read(last, size - 1)
            return last.array()[0] != '\n'.code.toByte()
        }

        private fun report(diagnostic: (String) -> Unit) {
            try { diagnostic(DIAGNOSTIC) } catch (_: Exception) { /* Diagnostics are isolated. */ }
        }
    }

    private fun warn() {
        if (warned.compareAndSet(false, true)) {
            report(diagnostic)
        }
    }
}
