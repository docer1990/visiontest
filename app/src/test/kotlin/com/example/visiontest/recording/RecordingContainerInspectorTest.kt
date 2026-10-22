package com.example.visiontest.recording

import com.example.visiontest.common.RequestDeadline
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import java.util.concurrent.TimeoutException
import java.nio.file.Path
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.io.TempDir

class RecordingContainerInspectorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `initialization does not require flushed video samples`() = runTest {
        val source = ByteArrayRecordingSource(initializedMovie())
        assertTrue(RecordingContainerInspector().initialized(source, RequestDeadline(1000)))
    }

    @Test
    fun `extended headers and unknown boxes are traversed by size`() = runTest {
        val bytes = movieBox("ftyp", "qt  ".toByteArray() + movieWords(0), extended = true) +
            movieBox("free", "mdat is not a header".toByteArray(), extended = true) +
            movieWords(1) + "mdat".toByteArray() + movieLong(1_000_000)
        val source = ByteArrayRecordingSource(bytes)
        assertTrue(RecordingContainerInspector().initialized(source, RequestDeadline(1000)))
        assertTrue(source.reads.all { (offset, count) -> count <= 4096 && offset + count <= bytes.size })
    }

    @Test
    fun `partial initialization returns false`() = runTest {
        val bytes = initializedMovie()
        for (length in 0 until bytes.size) {
            assertFalse(RecordingContainerInspector().initialized(
                ByteArrayRecordingSource(bytes.copyOf(length)), RequestDeadline(1000)
            ), "prefix $length")
        }
        val extended = movieFileType() + movieWords(1) + "mdat".toByteArray() + movieLong(100)
        assertFalse(RecordingContainerInspector().initialized(
            ByteArrayRecordingSource(extended.dropLast(1).toByteArray()), RequestDeadline(1000)
        ))
    }

    @Test
    fun `arbitrary media marker text does not initialize a movie`() = runTest {
        val bytes = movieFileType() + movieBox("free", "hello mdat world".toByteArray())
        assertFalse(RecordingContainerInspector().initialized(ByteArrayRecordingSource(bytes), RequestDeadline(1000)))
        assertFalse(RecordingContainerInspector().initialized(
            ByteArrayRecordingSource(movieBox("mdat")), RequestDeadline(1000)
        ))
    }

    @Test
    fun `malformed initialization has a specific error`() = runTest {
        val invalid = listOf(
            movieWords(4) + "mdat".toByteArray(),
            movieWords(1) + "mdat".toByteArray() + movieLong(15),
            movieWords(1) + "free".toByteArray() + movieLong(-1),
            movieWords(1) + "free".toByteArray() + movieLong(Long.MAX_VALUE),
            movieBox("ftyp", byteArrayOf(1)),
            movieWords(0) + "free".toByteArray(),
        )
        for (bytes in invalid) {
            val failure = assertFailsWith<RecordingException> {
                RecordingContainerInspector().initialized(
                    ByteArrayRecordingSource(movieFileType() + bytes), RequestDeadline(1000)
                )
            }
            assertTrue(failure.message.orEmpty().contains("recording", ignoreCase = true))
        }
    }

    @Test
    fun `fragmented initialization is unsupported`() = runTest {
        for (fragment in listOf(movieBox("moof"), movieBox("moov", movieBox("mvex")))) {
            val failure = assertFailsWith<RecordingException> {
                RecordingContainerInspector().initialized(
                    ByteArrayRecordingSource(movieFileType() + fragment + movieBox("mdat")), RequestDeadline(1000)
                )
            }
            assertTrue(failure.message.orEmpty().contains("fragmented"))
        }
    }

    @Test
    fun `startup traversal has a box work limit`() = runTest {
        val bytes = movieFileType() + ByteArray(10_001 * 8) { movieBox("free")[it % 8] } + movieBox("mdat")
        val failure = assertFailsWith<RecordingException> {
            RecordingContainerInspector().initialized(ByteArrayRecordingSource(bytes), RequestDeadline(1000))
        }
        assertTrue(failure.message.orEmpty().contains("box limit"))
    }

    @Test
    fun `container nesting has a depth limit`() = runTest {
        var nested = movieBox("free")
        repeat(10) { nested = movieBox("moov", nested) }
        val failure = assertFailsWith<RecordingException> {
            RecordingContainerInspector().initialized(
                ByteArrayRecordingSource(movieFileType() + nested + movieBox("mdat")), RequestDeadline(1000)
            )
        }
        assertTrue(failure.message.orEmpty().contains("nesting limit"))
    }

    @Test
    fun `expired startup deadline is preserved`() = runTest {
        assertFailsWith<TimeoutException> {
            RecordingContainerInspector().initialized(ByteArrayRecordingSource(initializedMovie()), RequestDeadline(0))
        }
    }

    @Test
    fun `deadline is checked after a source read`() = runTest {
        var now = 0L
        val deadline = RequestDeadline(1) { now }
        val bytes = ByteArrayRecordingSource(initializedMovie())
        val source = RecordingDataSource { offset, count, supplied ->
            bytes.read(offset, count, supplied).also { now += 1_000_000 }
        }
        assertFailsWith<TimeoutException> { RecordingContainerInspector().initialized(source, deadline) }
    }

    @Test
    fun `metadata before video does not invalidate a recording`() = runTest {
        validate(movieWithMetadataBeforeVideo())
    }

    @Test
    fun `per sample sizes resolve across multiple chunks and media boxes`() = runTest {
        val firstMedia = movieBox("mdat", byteArrayOf(1, 2, 3, 4, 5, 6))
        val secondMedia = movieBox("mdat", byteArrayOf(7, 8, 9), extended = true)
        val track = movieTrack(
            sampleSizes = longArrayOf(2, 4, 3),
            chunkOffsets = longArrayOf(28, 50),
            chunkEntries = listOf(Triple(1, 2, 1), Triple(2, 1, 1)),
        )
        validate(movieFileType() + firstMedia + secondMedia + movieBox("moov", track))
    }

    @Test
    fun `constant sample sizes and co64 offsets resolve video data`() = runTest {
        val track = movieTrack(
            sampleSizes = longArrayOf(),
            options = MovieTrackOptions(constantSize = 2, sampleCount = 3, wideOffsets = true),
            chunkOffsets = longArrayOf(28, 32),
            chunkEntries = listOf(Triple(1, 2, 1), Triple(2, 1, 1)),
        )
        validate(movieFileType() + movieBox("mdat", ByteArray(6)) + movieBox("moov", track, extended = true))
    }

    @Test
    fun `video offsets outside media data are rejected`() = runTest {
        rejects(movieWithInvalidVideoOffset(), "outside media data")
        rejects(movieFileType() + movieBox("mdat", ByteArray(4)) +
            movieBox("moov", movieTrack(sampleSizes = longArrayOf(5))), "outside media data")
    }

    @Test
    fun `empty and metadata only recordings fail validation`() = runTest {
        for (bytes in listOf(byteArrayOf(), initializedMovie(), movieFileType() + movieBox("mdat", ByteArray(4)) +
            movieBox("moov", movieTrack(handler = "meta")))) {
            rejects(bytes, "no nonempty video samples")
        }
    }

    @Test
    fun `zero video samples cannot validate a recording`() = runTest {
        for (track in listOf(
            movieTrack(sampleSizes = longArrayOf(0)),
            movieTrack(sampleSizes = longArrayOf(), chunkOffsets = longArrayOf(), chunkEntries = emptyList()),
        )) {
            rejects(movieFileType() + movieBox("mdat", ByteArray(4)) + movieBox("moov", track),
                "no nonempty video samples")
        }
    }

    @Test
    fun `chunk mappings must cover chunks in increasing order from one`() = runTest {
        val invalidMappings = listOf(
            listOf(Triple(2L, 1L, 1L)),
            listOf(Triple(0L, 1L, 1L)),
            listOf(Triple(1L, 1L, 1L), Triple(1L, 1L, 1L)),
            listOf(Triple(1L, 1L, 1L), Triple(3L, 1L, 1L)),
            listOf(Triple(1L, 1L, 0L)),
            listOf(Triple(1L, 1L, 2L)),
            listOf(Triple(1L, 0L, 1L)),
            listOf(Triple(1L, 0xffffffffL, 1L)),
        )
        for (mapping in invalidMappings) {
            rejects(movieFileType() + movieBox("mdat", ByteArray(4)) +
                movieBox("moov", movieTrack(chunkEntries = mapping)), "sample mapping")
        }
    }

    @Test
    fun `sample counts must agree with chunk mappings`() = runTest {
        for (track in listOf(
            movieTrack(sampleSizes = longArrayOf(2, 2)),
            movieTrack(chunkEntries = listOf(Triple(1, 2, 1))),
        )) {
            rejects(movieFileType() + movieBox("mdat", ByteArray(4)) + movieBox("moov", track), "sample count")
        }
    }

    @Test
    fun `corrupt sample table counts are rejected`() = runTest {
        val cases = listOf(
            movieTrack(options = MovieTrackOptions(sampleCount = 2)),
            movieTrack(options = MovieTrackOptions(sampleCount = 0)),
            movieTrack(options = MovieTrackOptions(extraTables = movieBox("stsz", movieWords(0, 4, 1)))),
            movieTrack(options = MovieTrackOptions(extraTables = movieBox("co64", movieWords(0, 1) + movieLong(28)))),
        )
        for (track in cases) {
            assertFailsWith<RecordingException> {
                validate(movieFileType() + movieBox("mdat", ByteArray(4)) + movieBox("moov", track))
            }
        }
    }

    @Test
    fun `table work is bounded even for constant sizes`() = runTest {
        rejects(movieFileType() + movieBox("mdat", ByteArray(4)) +
            movieBox("moov", movieTrack(options = MovieTrackOptions(constantSize = 4, sampleCount = 1_000_001))),
            "table work limit")
        rejects(movieFileType() + movieBox("mdat", ByteArray(4)) +
            movieBox("moov", movieTrack(options = MovieTrackOptions(constantSize = 4, sampleCount = 0xffffffffL))),
            "table work limit")
    }

    @Test
    fun `sample offset arithmetic cannot overflow`() = runTest {
        for (offset in listOf(-1L, Long.MAX_VALUE - 1)) {
            rejects(movieFileType() + movieBox("mdat", ByteArray(4)) +
                movieBox("moov", movieTrack(chunkOffsets = longArrayOf(offset),
                    options = MovieTrackOptions(wideOffsets = true))), "overflow")
        }
    }

    @Test
    fun `truncated completed containers always fail`() = runTest {
        val movie = movieWithMetadataBeforeVideo()
        for (length in movie.indices) {
            assertFailsWith<RecordingException>("prefix $length") { validate(movie.copyOf(length)) }
        }
        rejects(movie + movieWords(4) + "free".toByteArray(), "box size")
        rejects(movie + movieWords(9) + "moov".toByteArray() + byteArrayOf(0), "Truncated")
    }

    @Test
    fun `fragmented completed containers are unsupported`() = runTest {
        rejects(movieWithMetadataBeforeVideo() + movieBox("moof"), "fragmented")
        rejects(movieWithMetadataBeforeVideo() + movieBox("moov", movieBox("mvex")), "fragmented")
    }

    @Test
    fun `unsupported sample layouts fail explicitly`() = runTest {
        rejects(movieFileType() + movieBox("mdat", ByteArray(4)) +
            movieBox("moov", movieTrack(options = MovieTrackOptions(extraTables = movieBox("stz2")))), "Unsupported")
    }

    @Test
    fun `video handler must fit inside its own box`() = runTest {
        val track = movieBox("trak", movieBox("mdia", movieBox("hdlr", movieWords(0, 0) + "vide".toByteArray())))
        rejects(movieFileType() + movieBox("mdat", ByteArray(4)) + movieBox("moov", track), "handler")
    }

    @Test
    fun `expired final validation deadline is preserved`() = runTest {
        val path = directory.resolve("recording.mov")
        Files.write(path, movieWithMetadataBeforeVideo())
        assertFailsWith<TimeoutException> { RecordingContainerInspector().validateVideo(path, RequestDeadline(0)) }
    }

    @Test
    fun `blocked source reads are bounded by the supplied deadline`() = runTest {
        val result = withTimeoutOrNull(100) {
            assertFailsWith<TimeoutException> {
                RecordingContainerInspector().initialized(RecordingDataSource { _, _, _ -> awaitCancellation() },
                    RequestDeadline(1))
            }
        }
        assertNotNull(result)
    }

    @Test
    fun `multiple movie boxes are an unsupported layout`() = runTest {
        rejects(movieWithMetadataBeforeVideo() + movieBox("moov", movieTrack()), "movie box")
    }

    @Test
    fun `external media references cannot validate local samples`() = runTest {
        val reference = movieBox("dinf", movieBox("dref", movieWords(0, 1) +
            movieBox("url ", movieWords(0) + "file:///external.mov\u0000".toByteArray())))
        rejects(movieFileType() + movieBox("mdat", ByteArray(4)) + movieBox("moov",
            movieTrack(options = MovieTrackOptions(dataReference = reference))), "data reference")
    }

    @Test
    fun `self contained media references validate local samples`() = runTest {
        val reference = movieBox("dinf", movieBox("dref", movieWords(0, 1) + movieBox("url ", movieWords(1))))
        validate(movieFileType() + movieBox("mdat", ByteArray(4)) + movieBox("moov",
            movieTrack(options = MovieTrackOptions(dataReference = reference))))
    }

    @Test
    fun `chunk table counts and versions are checked`() = runTest {
        val cases = listOf(
            MovieTrackOptions(offsetCount = 2), MovieTrackOptions(offsetCount = 0),
            MovieTrackOptions(offsetCount = 0xffffffffL), MovieTrackOptions(mappingCount = 2),
            MovieTrackOptions(mappingCount = 0), MovieTrackOptions(mappingCount = 0xffffffffL),
            MovieTrackOptions(tableVersion = 0x01000000), MovieTrackOptions(tableVersion = 1),
        )
        for (options in cases) {
            assertFailsWith<RecordingException> {
                validate(movieFileType() + movieBox("mdat", ByteArray(4)) +
                    movieBox("moov", movieTrack(options = options)))
            }
        }
    }

    @Test
    fun `table limits apply across tables rather than to each table separately`() = runTest {
        rejects(movieFileType() + movieBox("mdat", ByteArray(4)) + movieBox("moov",
            movieTrack(options = MovieTrackOptions(constantSize = 1, sampleCount = 999_999))), "table work limit")
    }

    @Test
    fun `tables spanning multiple bounded reads resolve all samples`() = runTest {
        val track = movieTrack(
            sampleSizes = LongArray(3000) { if (it % 2 == 0) 1 else 3 },
            chunkOffsets = LongArray(1500) { 28L + 4 * it },
            chunkEntries = listOf(Triple(1, 2, 1)), options = MovieTrackOptions(wideOffsets = true),
        )
        validate(movieFileType() + movieBox("mdat", ByteArray(6000)) + movieBox("moov", track))
    }

    @Test
    fun `movie metadata may precede open ended media data`() = runTest {
        val placeholder = movieBox("moov", movieTrack())
        val offset = movieFileType().size + placeholder.size + 8L
        val movie = movieBox("moov", movieTrack(chunkOffsets = longArrayOf(offset)))
        validate(movieFileType() + movie + movieWords(0) + "mdat".toByteArray() + ByteArray(4))
    }

    @Test
    fun `an empty video track before a populated video track does not hide it`() = runTest {
        val emptyTrack = movieTrack(
            sampleSizes = longArrayOf(), chunkOffsets = longArrayOf(), chunkEntries = emptyList()
        )
        validate(movieFileType() + movieBox("mdat", ByteArray(4)) + movieBox("moov", emptyTrack + movieTrack()))
    }

    @Test
    fun `sparse video larger than four gigabytes is inspected with long offsets`() = runTest {
        val path = directory.resolve("large.mov")
        val sampleOffset = 0x1_0000_0020L
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            channel.write(ByteBuffer.wrap(movieFileType() + movieWords(1) + "mdat".toByteArray() +
                movieLong(sampleOffset + 4 - movieFileType().size)))
            channel.position(sampleOffset)
            channel.write(ByteBuffer.wrap(ByteArray(4) + movieBox("moov", movieTrack(
                chunkOffsets = longArrayOf(sampleOffset), options = MovieTrackOptions(wideOffsets = true)
            ))))
        }
        RecordingContainerInspector().validateVideo(path, RequestDeadline(1000))
    }

    @Test
    fun `source cancellation is not mistaken for partial initialization`() = runTest {
        assertFailsWith<CancellationException> {
            RecordingContainerInspector().initialized(RecordingDataSource { _, _, _ ->
                throw CancellationException("cancelled source")
            }, RequestDeadline(1000))
        }
    }

    @Test
    fun `unreadable final artifact has a recording error`() = runTest {
        val failure = assertFailsWith<RecordingException> {
            RecordingContainerInspector().validateVideo(directory.resolve("missing.mov"), RequestDeadline(1000))
        }
        assertTrue(failure.message.orEmpty().contains("Unable to read"))
    }

    @Test
    fun `video tracks require a sample description`() = runTest {
        rejects(movieWithOptions(MovieTrackOptions(sampleDescription = byteArrayOf())), "stsd")
    }

    @Test
    fun `sample descriptions require exactly one version zero entry`() = runTest {
        val descriptions = listOf(
            movieBox("stsd", movieWords(0)),
            movieAvcDescription(count = 0), movieAvcDescription(count = 2),
            movieAvcDescription(count = 0xffffffffL), movieAvcDescription(version = 0x01000000),
            movieAvcDescription(version = 1), movieAvcDescription(entry = movieAvcEntry() + movieAvcEntry()),
        )
        for (description in descriptions) {
            rejects(movieWithOptions(MovieTrackOptions(sampleDescription = description)), "sample description")
        }
    }

    @Test
    fun `duplicate sample descriptions are rejected`() = runTest {
        rejects(movieWithOptions(MovieTrackOptions(extraTables = movieAvcDescription())), "stsd")
    }

    @Test
    fun `unsupported video sample entries are rejected`() = runTest {
        for (type in listOf("hvc1", "avc3", "encv", "xxxx")) {
            rejects(movieWithOptions(MovieTrackOptions(sampleDescription =
                movieAvcDescription(movieAvcEntry(type = type)))), "Unsupported recording sample entry")
        }
    }

    @Test
    fun `visual sample entries require a complete valid header`() = runTest {
        val headers = listOf(
            movieVisualHeader().copyOf(77), movieVisualHeader().copyOf(8),
            movieVisualHeader().apply { this[0] = 1 },
            movieVisualHeader().apply { this[9] = 1 },
            movieVisualHeader().apply { this[24] = 0; this[25] = 0 },
            movieVisualHeader().apply { this[26] = 0; this[27] = 0 },
            movieVisualHeader().apply { this[40] = 0; this[41] = 0 },
        )
        for (header in headers) {
            rejects(movieWithOptions(MovieTrackOptions(sampleDescription =
                movieAvcDescription(movieAvcEntry(header = header, extensions = byteArrayOf())))), "visual sample")
        }
    }

    @Test
    fun `sample entry data reference indices must resolve to a local reference`() = runTest {
        for (reference in listOf(0, 2, 65535)) {
            rejects(movieWithOptions(MovieTrackOptions(sampleDescription =
                movieAvcDescription(movieAvcEntry(header = movieVisualHeader(reference))))), "data reference")
        }
    }

    @Test
    fun `video samples cannot omit their data reference table`() = runTest {
        rejects(movieWithOptions(MovieTrackOptions(dataReference = byteArrayOf())), "dinf")
        rejects(movieWithOptions(MovieTrackOptions(dataReference = movieBox("dinf"))), "dref")
    }

    @Test
    fun `quicktime self contained alias references support native AVC video`() = runTest {
        val header = movieVisualHeader().apply {
            "appl".toByteArray().copyInto(this, 12)
        }
        val track = movieTrack(options = MovieTrackOptions(
            dataReference = movieDataReference("alis"),
            sampleDescription = movieAvcDescription(movieAvcEntry(header = header)),
        ))
        val fileType = movieBox("ftyp", "qt  ".toByteArray() + movieWords(0) + "qt  ".toByteArray())
        validate(fileType + movieBox("mdat", ByteArray(4)) + movieBox("moov", track))
    }

    @Test
    fun `AVC sample entries require exactly one decoder configuration`() = runTest {
        val configuration = movieBox("avcC", movieAvcConfiguration())
        for (extensions in listOf(byteArrayOf(), configuration + configuration, movieBox("free", configuration))) {
            rejects(movieWithDescription(movieAvcEntry(extensions = extensions)), "AVC configuration")
        }
    }

    @Test
    fun `truncated AVC configurations are rejected within their own box`() = runTest {
        val configuration = movieAvcConfiguration()
        for (size in configuration.indices) {
            val extensions = movieBox("avcC", configuration.copyOf(size)) + movieBox("free", configuration)
            rejects(movieWithDescription(movieAvcEntry(extensions = extensions)), "AVC configuration")
        }
    }

    @Test
    fun `AVC configurations reject unsupported versions and invalid length fields`() = runTest {
        val invalid = listOf(
            movieAvcConfiguration().apply { this[0] = 2 },
            movieAvcConfiguration().apply { this[4] = 3 },
            movieAvcConfiguration().apply { this[4] = 0xfe.toByte() },
            movieAvcConfiguration().apply { this[5] = 1 },
            movieAvcConfiguration().apply { this[5] = 0xe0.toByte() },
            movieAvcConfiguration().apply { this[7] = 0 },
            movieAvcConfiguration().apply { this[6] = 0x7f; this[7] = 0xff.toByte() },
            movieAvcConfiguration().apply { this[8] = 0x68 },
            movieAvcConfiguration().apply { this[17] = 0 },
            movieAvcConfiguration().apply { this[19] = 0 },
            movieAvcConfiguration().apply { this[20] = 0x67 },
            movieAvcConfiguration() + byteArrayOf(0),
        )
        for (configuration in invalid) {
            rejects(movieWithDescription(movieAvcEntry(extensions = movieBox("avcC", configuration))),
                "AVC configuration")
        }
    }

    @Test
    fun `high profile AVC configuration extensions have bounded parameter sets`() = runTest {
        val highProfile = movieAvcConfiguration().apply { this[1] = 100 }
        val extension = byteArrayOf(0xfc.toByte(), 0xf8.toByte(), 0xf8.toByte(), 1, 0, 1, 0x6d)
        validate(movieWithDescription(movieAvcEntry(extensions = movieBox("avcC", highProfile + extension))))
        validate(movieWithDescription(movieAvcEntry(extensions = movieBox("avcC", highProfile))))
        for (size in 1 until extension.size) {
            rejects(movieWithDescription(movieAvcEntry(extensions =
                movieBox("avcC", highProfile + extension.copyOf(size)))), "AVC configuration")
        }
    }

    @Test
    fun `sample entry extension boxes must fit inside the entry`() = runTest {
        for (extensions in listOf(
            movieWords(1000) + "avcC".toByteArray(),
            movieWords(1) + "avcC".toByteArray(),
            byteArrayOf(0, 0, 0),
        )) {
            assertFailsWith<RecordingException> {
                validate(movieWithDescription(movieAvcEntry(extensions = extensions)))
            }
        }
    }

    @Test
    fun `protected sample entry extensions are unsupported`() = runTest {
        rejects(movieWithDescription(movieAvcEntry(extensions =
            movieBox("avcC", movieAvcConfiguration()) + movieBox("sinf"))), "Unsupported")
    }

    @Test
    fun `native AVC entries may carry well formed visual metadata boxes`() = runTest {
        val extensions = movieBox("pasp", movieWords(1, 1)) + movieBox("avcC", movieAvcConfiguration()) +
            movieBox("colr", "nclc".toByteArray() + byteArrayOf(0, 1, 0, 1, 0, 1))
        validate(movieWithDescription(movieAvcEntry(extensions = extensions)))
    }

    @Test
    fun `sample description entries cannot extend beyond their table`() = runTest {
        val entries = listOf(
            movieWords(1000) + "avc1".toByteArray() + movieVisualHeader(),
            movieWords(1) + "avc1".toByteArray() + movieLong(Long.MAX_VALUE),
            movieWords(0) + "avc1".toByteArray() + movieVisualHeader(),
        )
        for (entry in entries) {
            assertFailsWith<RecordingException> { validate(movieWithDescription(entry)) }
        }
    }

    @Test
    fun `invalid self reference tables cannot resolve the sample description`() = runTest {
        val entries = listOf(
            movieBox("dref", movieWords(0, 0)),
            movieBox("dref", movieWords(0, 2) + movieBox("url ", movieWords(1))),
            movieBox("dref", movieWords(1, 1) + movieBox("url ", movieWords(1))),
            movieBox("dref", movieWords(0, 1) + movieBox("alis", movieWords(0))),
            movieBox("dref", movieWords(0, 1) + movieBox("url ", movieWords(0x01000001))),
            movieBox("dref", movieWords(0, 1) + movieBox("url ", movieWords(1)) + movieBox("url ", movieWords(1))),
        )
        for (entry in entries) {
            rejects(movieWithOptions(MovieTrackOptions(dataReference = movieBox("dinf", entry))), "data reference")
        }
    }

    private fun movieWithDescription(entry: ByteArray): ByteArray =
        movieWithOptions(MovieTrackOptions(sampleDescription = movieAvcDescription(entry)))

    private fun movieWithOptions(options: MovieTrackOptions): ByteArray = movieFileType() +
        movieBox("mdat", ByteArray(4)) + movieBox("moov", movieTrack(options = options))

    private suspend fun rejects(bytes: ByteArray, message: String) {
        val failure = assertFailsWith<RecordingException> { validate(bytes) }
        assertTrue(failure.message.orEmpty().contains(message), failure.message)
    }

    private suspend fun validate(bytes: ByteArray) {
        val path = directory.resolve("recording.mp4")
        Files.write(path, bytes)
        RecordingContainerInspector().validateVideo(path, RequestDeadline(1000))
    }
}
