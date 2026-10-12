package com.example.visiontest.performance

import java.io.IOException
import java.io.Writer
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JsonlTraceSinkTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `normal finish drains before reserved terminal and is idempotent`() {
        val writer = StringWriter()
        val sink = JsonlTraceSink(writer, {})
        assertTrue(sink.offer("{\"type\":\"span\"}"))
        val result = sink.finish(1_000) { "{\"written\":${it.written},\"dropped\":${it.dropped}}" }
        assertEquals(TraceCloseResult(true, 1, 0), result)
        assertEquals(
            listOf("{\"type\":\"span\"}", "{\"written\":1,\"dropped\":0}"),
            writer.toString().lines().dropLast(1),
        )
        assertFalse(sink.offer("late"))
        assertEquals(result, sink.finish(0))
    }

    @Test
    fun `saturation drops without waiting and terminal bypasses full queue`() {
        val writer = BlockingWriter()
        val warnings = mutableListOf<String>()
        val sink = JsonlTraceSink(writer, warnings::add, capacity = 1)
        sink.offer("first")
        assertTrue(writer.entered.await(1, TimeUnit.SECONDS))
        assertTrue(sink.offer("second"))
        assertFalse(sink.offer("third"))
        assertFalse(sink.offer("fourth"))
        writer.release.countDown()
        val result = sink.finish(1_000) { "terminal:${it.written}:${it.dropped}:${it.complete}" }
        assertEquals(TraceCloseResult(false, 2, 2), result)
        assertEquals("first\nsecond\nterminal:2:2:false\n", writer.text.toString())
        assertEquals(1, warnings.size)
    }

    @Test
    fun `shutdown is bounded when writer ignores interruption`() {
        val writer = BlockingWriter()
        val warnings = mutableListOf<String>()
        val sink = JsonlTraceSink(writer, warnings::add)
        sink.offer("first")
        assertTrue(writer.entered.await(1, TimeUnit.SECONDS))
        sink.offer("second")
        val start = System.nanoTime()
        val result = sink.finish(25) { "terminal" }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
        assertFalse(result.complete)
        assertEquals(2, result.dropped)
        assertEquals(1, warnings.size)
        assertEquals(result, sink.finish(1_000))
        writer.release.countDown()
        assertTrue(writer.closed.await(1, TimeUnit.SECONDS))
        assertFalse(writer.text.toString().contains("terminal"))
    }

    @Test
    fun `final record failure and cleanup failure cannot certify completeness`() {
        for (failOnClose in listOf(false, true)) {
            val warnings = mutableListOf<String>()
            val sink = JsonlTraceSink(object : Writer() {
                override fun write(buffer: CharArray, offset: Int, count: Int) {
                    if (!failOnClose) throw IOException("secret-path")
                }
                override fun flush() = Unit
                override fun close() { if (failOnClose) throw IOException("secret-path") }
            }, warnings::add)
            assertFalse(sink.finish(1_000) { "terminal" }.complete)
            assertEquals(1, warnings.size)
            assertFalse(warnings.single().contains("secret-path"))
        }
    }

    @Test
    fun `factory creates parents appends and separates an interrupted last line`() {
        val path = directory.resolve("nested/trace.jsonl")
        Files.createDirectories(path.parent)
        Files.writeString(path, "{\"partial\":")
        val sink = assertNotNull(JsonlTraceSink.open(path, {}))
        sink.offer("{\"session\":1}")
        assertTrue(sink.finish(1_000).complete)
        assertEquals("{\"partial\":\n{\"session\":1}\n", Files.readString(path))
        val missing = directory.resolve("new/parents/trace.jsonl")
        assertTrue(assertNotNull(JsonlTraceSink.open(missing, {})).finish(1_000).complete)
    }

    @Test
    fun `exclusive ownership is per file and released on finish`() {
        val path = directory.resolve("trace.jsonl")
        val owner = assertNotNull(JsonlTraceSink.open(path, {}))
        val warnings = mutableListOf<String>()
        assertNull(JsonlTraceSink.open(path, warnings::add))
        assertEquals(1, warnings.size)
        assertFalse(warnings.single().contains(path.toString()))
        assertTrue(assertNotNull(JsonlTraceSink.open(directory.resolve("other.jsonl"), {})).finish(1_000).complete)
        assertTrue(owner.finish(1_000).complete)
        assertTrue(assertNotNull(JsonlTraceSink.open(path, {})).finish(1_000).complete)
    }

    @Test
    fun `factory rejects directories symlinks and special files`() {
        val regular = Files.writeString(directory.resolve("regular"), "unchanged")
        val link = Files.createSymbolicLink(directory.resolve("link"), regular)
        val fifo = directory.resolve("fifo")
        assertEquals(0, ProcessBuilder("mkfifo", fifo.toString()).start().waitFor())
        for (path in listOf(directory, link, fifo)) {
            val warnings = mutableListOf<String>()
            assertNull(JsonlTraceSink.open(path, warnings::add))
            assertEquals(1, warnings.size)
        }
        assertEquals("unchanged", Files.readString(regular))
    }

    @Test
    fun `another JVM cannot acquire an owned file but can own another path`() {
        val source = directory.resolve("TraceLockProbe.java")
        Files.writeString(source, """
            import java.nio.channels.*;
            import java.nio.file.*;
            class TraceLockProbe {
                public static void main(String[] args) throws Exception {
                    try (FileChannel channel = FileChannel.open(Path.of(args[0]),
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                        FileLock lock = channel.tryLock();
                        System.out.println(lock == null ? "conflict" : "owned");
                        System.out.flush();
                        if (lock != null) {
                            System.in.read();
                            lock.release();
                        }
                    }
                }
            }
        """.trimIndent())
        val path = directory.resolve("parent.jsonl")
        val owner = assertNotNull(JsonlTraceSink.open(path, {}))
        try {
            assertNull(JsonlTraceSink.open(path, {}))
            for ((target, expected) in listOf(path to "conflict", directory.resolve("child.jsonl") to "owned")) {
                assertProcessOwnership(source, target, expected)
            }
        } finally { owner.finish(1_000) }
    }

    private fun assertProcessOwnership(source: Path, target: Path, expected: String) {
        val child = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            source.toString(), target.toString(),
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            assertEquals(expected, child.inputStream.bufferedReader().readLine())
            if (expected == "owned") assertNull(JsonlTraceSink.open(target, {}))
            child.outputStream.close()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
        } finally { child.destroyForcibly() }
    }

    @Test
    fun `blocking flush and close leave shutdown bounded`() {
        for (blockFlush in listOf(true, false)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val sink = JsonlTraceSink(object : Writer() {
                override fun write(buffer: CharArray, offset: Int, count: Int) = Unit
                override fun flush() { if (blockFlush) block() }
                override fun close() {
                    if (!blockFlush) block()
                    closed.countDown()
                }
                private fun block() {
                    entered.countDown()
                    var released = false
                    while (!released) {
                        try { release.await(); released = true } catch (_: InterruptedException) { /* Deliberate. */ }
                    }
                }
            }, {})
            val start = System.nanoTime()
            assertFalse(sink.finish(25).complete)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(closed.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `expired flush does not admit a terminal record`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val text = StringWriter()
        val sink = JsonlTraceSink(object : Writer() {
            override fun write(buffer: CharArray, offset: Int, count: Int) = text.write(buffer, offset, count)
            override fun flush() {
                entered.countDown()
                var released = false
                while (!released) {
                    try { release.await(); released = true } catch (_: InterruptedException) { /* Deliberate. */ }
                }
            }
            override fun close() { closed.countDown() }
        }, {})
        assertFalse(sink.finish(25) { "terminal" }.complete)
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(closed.await(1, TimeUnit.SECONDS))
        assertEquals("", text.toString())
    }

    @Test
    fun `diagnostic failures never escape or recur`() {
        var warnings = 0
        val sink = JsonlTraceSink(object : Writer() {
            override fun write(buffer: CharArray, offset: Int, count: Int) { throw IOException("secret") }
            override fun flush() = Unit
            override fun close() = Unit
        }, { warnings++; error("secret") })
        sink.offer("event")
        assertEquals(TraceCloseResult(false, 0, 1), sink.finish(1_000))
        assertFalse(sink.offer("late"))
        sink.finish(1_000)
        assertEquals(1, warnings)
        assertNull(JsonlTraceSink.open(directory, { error("secret") }))
    }

    @Test
    fun `persisted typed events exclude nearby sensitive inputs`() = runTest {
        val writer = StringWriter()
        val sink = JsonlTraceSink(writer, {})
        val secrets = listOf("synthetic-argument", "private-UI-label", "/secret/path", "secret-exception")
        val recorder = TraceRecorder(emit = { sink.offer(it.toJson().toString()); Unit })
        for (secret in secrets) {
            runCatching { recorder.invocation(secret, secret) { throw IOException(secret) } }
        }
        assertTrue(sink.finish(1_000).complete)
        secrets.forEach { assertFalse(writer.toString().contains(it)) }
    }

    private class BlockingWriter : Writer() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val text = StringWriter()
        override fun write(buffer: CharArray, offset: Int, count: Int) {
            entered.countDown()
            var released = false
            while (!released) {
                try { release.await(); released = true } catch (_: InterruptedException) { /* Deliberately blocked. */ }
            }
            text.write(buffer, offset, count)
        }
        override fun flush() = Unit
        override fun close() { closed.countDown() }
    }

    @Test
    fun `writer failure is reported without escaping to the operation`() {
        val warnings = mutableListOf<String>()
        val sink = JsonlTraceSink(
            writer = object : Writer() {
                override fun write(buffer: CharArray, offset: Int, count: Int) {
                    throw IOException("secret-path")
                }
                override fun flush() = Unit
                override fun close() = Unit
            },
            diagnostic = warnings::add,
        )
        sink.offer("{\"type\":\"session\",\"version\":1}")
        val result = sink.finish(1_000)
        assertFalse(result.complete)
        assertEquals(1, warnings.size)
        assertFalse(warnings.single().contains("secret-path"))
    }
}
