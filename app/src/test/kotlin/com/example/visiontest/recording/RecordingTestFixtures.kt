package com.example.visiontest.recording

import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CountDownLatch

internal fun completedProcess(stdout: String, stderr: String, exitCode: Int): Process {
    val process = mockk<Process>()
    every { process.inputStream } returns stdout.byteInputStream()
    every { process.errorStream } returns stderr.byteInputStream()
    every { process.outputStream } returns ByteArrayOutputStream()
    every { process.onExit() } returns CompletableFuture.completedFuture(process)
    every { process.exitValue() } returns exitCode
    every { process.isAlive } returns false
    every { process.pid() } returns 2_000_000_000L
    return process
}

internal class GatedStream : InputStream() {
    private val bytes = LinkedBlockingQueue<Int>()
    private var ended = false
    val reading = CompletableDeferred<Unit>()

    fun emit(text: String) = text.toByteArray().forEach { bytes.put(it.toInt() and 0xff) }
    fun finish() { bytes.put(-1) }

    override fun read(): Int {
        reading.complete(Unit)
        if (ended) return -1
        return bytes.take().also { if (it == -1) ended = true }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return when {
            length == 0 -> 0
            else -> readAvailable(buffer, offset, length)
        }
    }

    private fun readAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
        val first = read()
        if (first == -1) return -1
        buffer[offset] = first.toByte()
        var count = 1
        var next = if (count < length) bytes.poll() else null
        while (next != null && next != -1) {
            buffer[offset + count++] = next.toByte()
            next = if (count < length) bytes.poll() else null
        }
        if (next == -1) ended = true
        return count
    }

    override fun close() = finish()
}

internal class GatedProcess {
    val stdout = GatedStream()
    val stderr = GatedStream()
    val process = mockk<Process>()
    val reaped = CompletableFuture<Process>()
    val terminated = CompletableDeferred<Unit>()
    private val alive = AtomicBoolean(true)
    private var code = 0

    init {
        every { process.inputStream } returns stdout
        every { process.errorStream } returns stderr
        every { process.outputStream } returns ByteArrayOutputStream()
        every { process.onExit() } returns reaped
        every { process.isAlive } answers { alive.get() }
        every { process.pid() } returns 2_000_000_000L
        every { process.exitValue() } answers {
            check(!alive.get())
            code
        }
        every { process.destroyForcibly() } answers { finish(137); process }
    }

    fun finish(exitCode: Int = 0, closePipes: Boolean = true) {
        code = exitCode
        alive.set(false)
        if (closePipes) { stdout.finish(); stderr.finish() }
        reaped.complete(process)
        terminated.complete(Unit)
    }
}

internal class StubbornStream : InputStream() {
    private val release = CountDownLatch(1)
    val reading = CompletableDeferred<Unit>()

    override fun read(): Int {
        reading.complete(Unit)
        while (release.count > 0) {
            try { release.await() } catch (_: InterruptedException) { continue }
        }
        return -1
    }

    fun finish() = release.countDown()
}
