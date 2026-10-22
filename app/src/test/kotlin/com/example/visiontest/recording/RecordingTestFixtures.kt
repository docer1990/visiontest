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
import com.example.visiontest.common.RequestDeadline
import java.nio.ByteBuffer

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

internal class ByteArrayRecordingSource(private val bytes: ByteArray) : RecordingDataSource {
    val reads = mutableListOf<Pair<Long, Int>>()

    override suspend fun read(offset: Long, maxBytes: Int, deadline: RequestDeadline): ByteArray {
        reads += offset to maxBytes
        if (offset >= bytes.size) return byteArrayOf()
        return bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + maxBytes).toInt())
    }
}

internal fun movieWords(vararg values: Long): ByteArray =
    ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it.toInt()) } }.array()

internal fun movieLong(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()

internal fun movieBox(type: String, payload: ByteArray = byteArrayOf(), extended: Boolean = false): ByteArray =
    if (extended) movieWords(1) + type.toByteArray() + movieLong(payload.size + 16L) + payload
    else movieWords(payload.size + 8L) + type.toByteArray() + payload

internal fun movieFileType(): ByteArray = movieBox("ftyp", "isom".toByteArray() + movieWords(0) + "isom".toByteArray())

internal fun initializedMovie(): ByteArray = movieFileType() + movieWords(0) + "mdat".toByteArray()

internal data class MovieTrackOptions(
    val constantSize: Long = 0,
    val sampleCount: Long? = null,
    val wideOffsets: Boolean = false,
    val extraTables: ByteArray = byteArrayOf(),
    val offsetCount: Long? = null,
    val mappingCount: Long? = null,
    val tableVersion: Long = 0,
    val dataReference: ByteArray = movieDataReference(),
    val sampleDescription: ByteArray? = null,
)

internal fun movieTrack(
    handler: String = "vide",
    sampleSizes: LongArray = longArrayOf(4),
    chunkOffsets: LongArray = longArrayOf(28),
    chunkEntries: List<Triple<Long, Long, Long>> = listOf(Triple(1L, 1L, 1L)),
    options: MovieTrackOptions = MovieTrackOptions(),
): ByteArray {
    val handlerBox = movieBox("hdlr", movieWords(0, 0) + handler.toByteArray() + movieWords(0, 0, 0))
    val sizes = movieBox("stsz", movieWords(options.tableVersion, options.constantSize,
        options.sampleCount ?: sampleSizes.size.toLong()) +
        if (options.constantSize == 0L) movieWords(*sampleSizes) else byteArrayOf())
    val offsets = movieBox(if (options.wideOffsets) "co64" else "stco",
        movieWords(0, options.offsetCount ?: chunkOffsets.size.toLong()) +
        if (options.wideOffsets) chunkOffsets.fold(byteArrayOf()) { bytes, offset -> bytes + movieLong(offset) }
        else movieWords(*chunkOffsets))
    val mapping = movieBox("stsc", movieWords(0, options.mappingCount ?: chunkEntries.size.toLong()) +
        chunkEntries.fold(byteArrayOf()) { bytes, (first, count, description) ->
            bytes + movieWords(first, count, description)
        })
    val description = options.sampleDescription ?: if (handler == "vide") movieAvcDescription() else {
        movieBox("stsd", movieWords(0, 1) + movieBox("mett",
            ByteArray(6) + byteArrayOf(0, 1, 0) + "application/octet-stream\u0000".toByteArray()))
    }
    return movieBox("trak", movieBox("mdia", handlerBox + movieBox("minf",
        options.dataReference + movieBox("stbl", description + sizes + offsets + mapping + options.extraTables))))
}

internal fun movieDataReference(type: String = "url "): ByteArray =
    movieBox("dinf", movieBox("dref", movieWords(0, 1) + movieBox(type, movieWords(1))))

internal fun movieVisualHeader(reference: Int = 1): ByteArray = ByteBuffer.allocate(78).apply {
    put(ByteArray(6)).putShort(reference.toShort())
    putShort(0).putShort(0).putInt(0)
    putInt(0).putInt(0)
    putShort(640).putShort(360)
    putInt(0x00480000).putInt(0x00480000)
    putInt(0).putShort(1)
    put(ByteArray(32)).putShort(24).putShort(-1)
}.array()

internal fun movieAvcConfiguration(): ByteArray = byteArrayOf(
    1, 66, 0, 30, 0xff.toByte(), 0xe1.toByte(),
    0, 9, 0x67, 0x42, 0, 0x1e, 0xe9.toByte(), 1, 0x40, 0x7b, 0x20,
    1, 0, 4, 0x68, 0xce.toByte(), 0x3c, 0x80.toByte(),
)

internal fun movieAvcEntry(
    type: String = "avc1",
    header: ByteArray = movieVisualHeader(),
    extensions: ByteArray = movieBox("avcC", movieAvcConfiguration()),
): ByteArray = movieBox(type, header + extensions)

internal fun movieAvcDescription(
    entry: ByteArray = movieAvcEntry(),
    count: Long = 1,
    version: Long = 0,
): ByteArray = movieBox("stsd", movieWords(version, count) + entry)

internal fun movieWithMetadataBeforeVideo(): ByteArray = movieFileType() +
    movieBox("mdat", byteArrayOf(9, 9, 9, 9, 1, 2, 3, 4)) +
    movieBox("moov", movieTrack(handler = "meta") + movieTrack(chunkOffsets = longArrayOf(32)))

internal fun movieWithInvalidVideoOffset(): ByteArray = movieFileType() +
    movieBox("mdat", byteArrayOf(1, 2, 3, 4)) + movieBox("moov", movieTrack(chunkOffsets = longArrayOf(24)))
