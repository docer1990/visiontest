package com.example.visiontest.recording

import com.example.visiontest.common.RequestDeadline
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.coroutines.coroutineContext

internal class RecordingContainerInspector {
    suspend fun validateVideo(path: Path, deadline: RequestDeadline) {
        try {
            withContext(Dispatchers.IO) {
                deadline.remainingMillis()
                FileChannel.open(path).use { channel ->
                    val reader = ContainerReader(ChannelRecordingSource(channel), deadline)
                    val boxes = reader.movie(channel.size())
                    val media = boxes.filter { it.type == "mdat" }
                    val movies = boxes.filter { it.type == "moov" }
                    if (movies.size > 1) invalid("Unsupported multiple recording movie boxes")
                    val tracks = movies.flatMap { it.children }.filter { it.type == "trak" }
                    val samples = VideoSampleReader(reader, deadline)
                    var found = false
                    for (track in tracks) {
                        if (samples.videoTrack(track, media)) found = true
                    }
                    if (!found) invalid("Recording contains no nonempty video samples")
                }
            }
        } catch (failure: IncompleteContainer) {
            throw RecordingException("Truncated recording container", failure)
        } catch (failure: IOException) {
            throw RecordingException("Unable to read recording container", failure)
        }
    }

    suspend fun initialized(source: RecordingDataSource, deadline: RequestDeadline): Boolean {
        val reader = ContainerReader(source, deadline)
        try {
            var offset = 0L
            var fileType = false
            while (true) {
                val box = reader.header(offset)
                if (box.type == "mdat") return fileType
                reader.inspect(box)
                if (box.type == "ftyp") fileType = true
                offset = box.end ?: invalid("Unsupported open-ended recording box")
            }
        } catch (_: IncompleteContainer) {
            return false
        }
    }
}

private data class ContainerBox(
    val type: String,
    val start: Long,
    val end: Long?,
    val children: List<ContainerBox> = emptyList(),
) {
    fun child(type: String): ContainerBox = children.singleOrNull { it.type == type }
        ?: invalid("Missing or duplicate recording $type box")
}

private class IncompleteContainer : Exception()

private class ContainerReader(private val source: RecordingDataSource, private val deadline: RequestDeadline) {
    private var boxes = 0

    suspend fun read(offset: Long, count: Int): ByteArray {
        coroutineContext.ensureActive()
        deadline.remainingMillis()
        val bytes = withTimeoutOrNull(deadline.remainingMillis()) { source.read(offset, count, deadline) }
            ?: throw TimeoutException("Recording inspection deadline exceeded")
        coroutineContext.ensureActive()
        deadline.remainingMillis()
        if (bytes.size != count) throw IncompleteContainer()
        return bytes
    }

    suspend fun header(offset: Long, parentEnd: Long? = null): ContainerBox {
        checkHeaderSpace(offset, parentEnd, HEADER_SIZE)
        val bytes = ByteBuffer.wrap(read(offset, HEADER_SIZE))
        val shortSize = bytes.int.toLong() and UINT_MASK
        val typeBytes = ByteArray(TYPE_SIZE).also { bytes.get(it) }
        val type = String(typeBytes, Charsets.US_ASCII)
        val headerSize = if (shortSize == 1L) EXTENDED_HEADER_SIZE else HEADER_SIZE
        checkHeaderSpace(offset, parentEnd, headerSize)
        val size = if (shortSize == 1L) {
            ByteBuffer.wrap(read(add(offset, HEADER_SIZE.toLong()), LONG_SIZE)).long
        } else shortSize
        val end = boxEnd(offset, size, headerSize, type, parentEnd)
        if (parentEnd != null && (end == null || end > parentEnd)) invalid("Recording box exceeds its parent")
        if (++boxes > MAX_BOXES) invalid("Recording box limit exceeded")
        if (type in FRAGMENT_BOXES) invalid("Unsupported fragmented recording")
        return ContainerBox(type, add(offset, headerSize.toLong()), end)
    }

    private fun checkHeaderSpace(offset: Long, parentEnd: Long?, headerSize: Int) {
        if (parentEnd != null && parentEnd - offset < headerSize) invalid("Truncated recording box header")
    }

    private fun boxEnd(offset: Long, size: Long, headerSize: Int, type: String, parentEnd: Long?): Long? = when {
        size == 0L && headerSize == HEADER_SIZE && type == "mdat" && parentEnd == null -> null
        size < headerSize -> invalid("Invalid recording box size for $type")
        else -> add(offset, size)
    }

    suspend fun inspect(box: ContainerBox, depth: Int = 0): ContainerBox {
        if (depth >= MAX_DEPTH) invalid("Recording nesting limit exceeded")
        val end = box.end ?: invalid("Unsupported open-ended recording box")
        val children = mutableListOf<ContainerBox>()
        if (box.type in CONTAINER_BOXES) {
            var offset = box.start
            while (offset < end) {
                val child = header(offset, end)
                children += inspect(child, depth + 1)
                offset = child.end ?: invalid("Unsupported open-ended recording box")
            }
        } else {
            if (box.type == "ftyp" && (end - box.start < HEADER_SIZE || (end - box.start) % TYPE_SIZE != 0L)) {
                invalid("Invalid recording file type box")
            }
            if (end > box.start) read(end - 1, 1)
        }
        return box.copy(children = children)
    }

    suspend fun movie(size: Long): List<ContainerBox> {
        val result = mutableListOf<ContainerBox>()
        var offset = 0L
        while (offset < size) {
            val box = header(offset).let { if (it.end == null) it.copy(end = size) else it }
            val end = box.end ?: invalid("Missing recording box end")
            if (end > size) throw IncompleteContainer()
            result += inspect(box)
            offset = end
        }
        return result
    }
}

private class VideoSampleReader(private val reader: ContainerReader, private val deadline: RequestDeadline) {
    private var entries = 0L

    suspend fun videoTrack(track: ContainerBox, media: List<ContainerBox>): Boolean {
        val mdia = track.child("mdia")
        val handler = mdia.child("hdlr")
        if ((handler.end ?: 0) - handler.start < HANDLER_SIZE) invalid("Truncated recording handler box")
        val type = reader.read(add(handler.start, HEADER_SIZE.toLong()), TYPE_SIZE).toString(Charsets.US_ASCII)
        if (type != "vide") return false
        val mediaInfo = mdia.child("minf")
        validateDataReference(mediaInfo)
        val tables = mediaInfo.child("stbl")
        if (tables.children.any { it.type in UNSUPPORTED_TABLES }) invalid("Unsupported recording sample layout")
        val sizes = sampleSizes(tables.child("stsz"))
        val offsets = tables.children.filter { it.type == "stco" || it.type == "co64" }.singleOrNull()
            ?: invalid("Missing or duplicate recording chunk offsets")
        val chunks = chunkOffsets(offsets)
        val mappings = sampleMapping(tables.child("stsc"))
        return resolveSamples(sizes, chunks, mappings, media)
    }

    private suspend fun validateDataReference(mediaInfo: ContainerBox) {
        if (mediaInfo.children.none { it.type == "dinf" }) return
        val references = mediaInfo.child("dinf").child("dref")
        if ((references.end ?: 0) - references.start < HEADER_SIZE) invalid("Truncated recording data reference")
        val header = ByteBuffer.wrap(reader.read(references.start, HEADER_SIZE))
        if (header.int != 0 || header.int != 1) invalid("Unsupported recording data reference table")
        val reference = reader.header(add(references.start, HEADER_SIZE.toLong()), references.end)
        val validUrl = reference.type == "url " && reference.end == references.end
        if (!validUrl || (reference.end ?: 0) - reference.start != TYPE_SIZE.toLong()) {
            invalid("Unsupported recording data reference")
        }
        if (ByteBuffer.wrap(reader.read(reference.start, TYPE_SIZE)).int != 1) {
            invalid("Unsupported external recording data reference")
        }
    }

    private suspend fun sampleSizes(box: ContainerBox): LongArray {
        val table = TableCursor(reader, box)
        table.version()
        val constant = table.word()
        val count = entryCount(table.word())
        val sizes = LongArray(count) { constant }
        if (constant == 0L) {
            for (index in sizes.indices) sizes[index] = table.word()
        }
        table.finished()
        return sizes
    }

    private suspend fun chunkOffsets(box: ContainerBox): LongArray {
        val table = TableCursor(reader, box)
        table.version()
        val result = LongArray(entryCount(table.word()))
        for (index in result.indices) {
            result[index] = if (box.type == "co64") table.longWord() else table.word()
        }
        table.finished()
        return result
    }

    private suspend fun sampleMapping(box: ContainerBox): List<ChunkMapping> {
        val table = TableCursor(reader, box)
        table.version()
        val count = entryCount(table.word())
        val result = mutableListOf<ChunkMapping>()
        repeat(count) { result += ChunkMapping(table.word(), table.word(), table.word()) }
        table.finished()
        return result
    }

    private fun entryCount(count: Long): Int {
        entries += count
        if (entries > MAX_TABLE_ENTRIES) invalid("Recording table work limit exceeded")
        return count.toInt()
    }

    private suspend fun resolveSamples(
        sizes: LongArray,
        chunks: LongArray,
        mappings: List<ChunkMapping>,
        media: List<ContainerBox>,
    ): Boolean {
        validateMappings(mappings, chunks.size)
        var sample = 0
        var mapping = 0
        for (index in chunks.indices) {
            deadline.remainingMillis()
            coroutineContext.ensureActive()
            if (mapping + 1 < mappings.size && mappings[mapping + 1].firstChunk == index + 1L) mapping++
            val count = mappings.getOrNull(mapping)?.samples ?: invalid("Missing recording sample mapping")
            var end = chunks[index]
            repeat(count.toInt()) {
                deadline.remainingMillis()
                coroutineContext.ensureActive()
                if (sample >= sizes.size) invalid("Recording sample count mismatch")
                end = add(end, sizes[sample++])
            }
            if (media.none { chunks[index] >= it.start && end <= (it.end ?: 0) }) {
                invalid("Recording video samples outside media data")
            }
        }
        if (sample != sizes.size) invalid("Recording sample count mismatch")
        return sizes.any { it > 0 }
    }

    private fun validateMappings(mappings: List<ChunkMapping>, chunks: Int) {
        if (chunks > 0 && mappings.firstOrNull()?.firstChunk != 1L) invalid("Invalid recording sample mapping origin")
        var previous = 0L
        for (mapping in mappings) {
            val invalidSamples = mapping.samples <= 0 || mapping.samples > MAX_TABLE_ENTRIES
            val invalidChunk = mapping.firstChunk <= previous || mapping.firstChunk > chunks
            if (invalidChunk || invalidSamples || mapping.description != 1L) {
                invalid("Unsupported or invalid recording sample mapping")
            }
            previous = mapping.firstChunk
        }
    }
}

private data class ChunkMapping(val firstChunk: Long, val samples: Long, val description: Long)

private class TableCursor(private val reader: ContainerReader, private val box: ContainerBox) {
    private var offset = box.start
    private var buffer = ByteBuffer.allocate(0)

    suspend fun word(): Long = bytes(TYPE_SIZE).int.toLong() and UINT_MASK

    suspend fun longWord(): Long = bytes(LONG_SIZE).long.also {
        if (it < 0) invalid("Recording offset overflow")
    }

    suspend fun version() {
        if (word() != 0L) invalid("Unsupported recording table version or flags")
    }

    fun finished() {
        if (offset != box.end) invalid("Recording table count does not match box size")
    }

    private suspend fun bytes(count: Int): ByteBuffer {
        val remaining = (box.end ?: 0) - offset
        if (remaining < count) invalid("Truncated recording sample table")
        if (buffer.remaining() < count) {
            buffer = ByteBuffer.wrap(reader.read(offset, minOf(remaining, MAX_READ_BYTES.toLong()).toInt()))
        }
        offset += count
        return buffer
    }
}

private fun invalid(message: String): Nothing = throw RecordingException(message)

private fun add(left: Long, right: Long): Long {
    if (right < 0 || left > Long.MAX_VALUE - right) invalid("Recording offset overflow")
    return left + right
}

private const val HEADER_SIZE = 8
private const val EXTENDED_HEADER_SIZE = 16
private const val TYPE_SIZE = 4
private const val LONG_SIZE = 8
private const val UINT_MASK = 0xffffffffL
private const val MAX_BOXES = 10_000
private const val MAX_DEPTH = 8
private const val MAX_READ_BYTES = 4096
private const val MAX_TABLE_ENTRIES = 1_000_000
private const val HANDLER_SIZE = 24
private val CONTAINER_BOXES = setOf("moov", "trak", "mdia", "minf", "stbl", "dinf")
private val FRAGMENT_BOXES = setOf("moof", "mvex", "traf")
private val UNSUPPORTED_TABLES = setOf("stz2", "senc", "saiz", "saio")
