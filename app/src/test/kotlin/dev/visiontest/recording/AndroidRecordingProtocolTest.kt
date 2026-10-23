package dev.visiontest.recording

import dev.visiontest.common.RequestDeadline
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidRecordingProtocolTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `generated signal guards reject corrupt ownership without signalling either child`() = runTest {
        val scripts = scripts()
        for (corruption in listOf("token", "pid", "zero", "negative", "nonnumeric", "cmdline")) {
            LocalRecordingProtocol(directory.resolve(corruption), scripts).use { local ->
                val child = local.startRecorder()
                val unrelated = local.startUnrelated()
                local.writeOwner(child.pid())
                when (corruption) {
                    "token" -> local.writeOwner(child.pid(), "unrelated-token")
                    "pid" -> {
                        local.writeOwner(unrelated.pid())
                        Files.createDirectories(local.commandLine(unrelated.pid()).parent)
                        Files.copy(local.commandLine(child.pid()), local.commandLine(unrelated.pid()))
                    }
                    "zero" -> local.writeOwner("0")
                    "negative" -> local.writeOwner("-1")
                    "nonnumeric" -> local.writeOwner("not-a-pid")
                    "cmdline" -> Files.writeString(local.commandLine(child.pid()), "unrelated-process\u0000")
                }
                for (signal in listOf(scripts.interrupt, scripts.forceStop)) {
                    assertEquals(1, local.run(signal, child.pid()).exitCode, corruption)
                    assertFalse(Files.exists(local.signalLog), corruption)
                    assertTrue(child.isAlive, corruption)
                    assertTrue(unrelated.isAlive, corruption)
                }
            }
        }
    }

    @Test
    fun `generated signal commands stop only the verified recorder`() = runTest {
        val scripts = scripts()
        for ((name, signal) in listOf("INT" to scripts.interrupt, "KILL" to scripts.forceStop)) {
            LocalRecordingProtocol(directory.resolve(name), scripts).use { local ->
                val child = local.startRecorder()
                val unrelated = local.startUnrelated()
                local.writeOwner(child.pid())
                val result = local.run(signal, child.pid())
                assertEquals(0, result.exitCode, result.stderr)
                assertTrue(child.waitFor(5, TimeUnit.SECONDS))
                assertEquals("-$name ${child.pid()}\n", Files.readString(local.signalLog))
                assertEquals(name == "INT", Files.exists(local.interrupted))
                assertTrue(unrelated.isAlive)
            }
        }
    }

    @Test
    fun `generated wrapper traps reap the recorder and publish completion on interruption`() = runTest {
        val scripts = scripts()
        for (signal in listOf("HUP", "INT", "TERM")) {
            LocalRecordingProtocol(directory.resolve(signal), scripts).use { local ->
                val unrelated = local.startUnrelated()
                val wrapper = local.startWrapper()
                val childPid = local.awaitRecorder()
                assertEquals("${scripts.token} $childPid", local.owner().trim())
                assertEquals(0, local.signalWrapper(wrapper, signal).exitCode)
                assertTrue(wrapper.waitFor(5, TimeUnit.SECONDS), "Wrapper did not finish after $signal")
                assertTrue(Files.exists(local.interrupted), "Recorder did not receive INT after $signal")
                assertEquals("7\n", Files.readString(local.remote.resolve("done")))
                assertFalse(Files.exists(local.remote.resolve("done.tmp")))
                assertFalse(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false))
                assertTrue(unrelated.isAlive)
            }
        }
    }

    @Test
    fun `wrapper cleanup rejects a child whose command identity changed`() = runTest {
        val scripts = scripts()
        LocalRecordingProtocol(directory, scripts).use { local ->
            val unrelated = local.startUnrelated()
            val wrapper = local.startWrapper()
            val childPid = local.awaitRecorder()
            Files.writeString(local.commandLine(childPid), "unrelated-process\u0000")
            assertEquals(0, local.signalWrapper(wrapper, "HUP").exitCode)
            local.awaitCleanupGuard()
            assertFalse(Files.exists(local.interrupted))
            assertTrue(ProcessHandle.of(childPid).orElseThrow().isAlive)
            assertTrue(unrelated.isAlive)
            local.releaseRecorder()
            assertTrue(wrapper.waitFor(5, TimeUnit.SECONDS))
            assertEquals("0\n", Files.readString(local.remote.resolve("done")))
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.scripts(): GeneratedRecordingScripts {
        val runner = FakeAndroidRecordingProcesses()
        val session = AndroidRecordingSession(backgroundScope, directory.resolve("capture"), 150_000, runner)
        session.start(RequestDeadline(1000))
        runner.signalCode = 1
        runCatching { session.finish(RequestDeadline(1000)) }
        return GeneratedRecordingScripts(runner.remote, runner.token, runner.wrapper,
            runner.commands.first { "-T" in it && it.last().contains("kill -INT") }.last(),
            runner.commands.first { "-T" in it && it.last().contains("kill -KILL") }.last())
    }
}

private data class GeneratedRecordingScripts(
    val remote: String,
    val token: String,
    val wrapper: String,
    val interrupt: String,
    val forceStop: String,
)

private class LocalRecordingProtocol(
    private val root: Path,
    private val scripts: GeneratedRecordingScripts,
) : AutoCloseable {
    val remote = root.resolve("remote")
    val signalLog = root.resolve("signals")
    val interrupted = root.resolve("interrupted")
    private val executable = root.resolve("screenrecord")
    private val ready = root.resolve("ready")
    private val release = root.resolve("release")
    private val guardRead = root.resolve("guard-read")
    private val children = mutableListOf<Process>()
    private var recorderPid: Long? = null

    init {
        Files.createDirectories(root)
        Files.writeString(executable, """
            #!/usr/bin/env python3
            import os, pathlib, signal, sys, time
            root = pathlib.Path('$root')
            def interrupted(signum, frame):
                (root / 'interrupted').write_text(str(signum))
                sys.exit(7)
            signal.signal(signal.SIGINT, interrupted)
            proc = root / 'proc' / str(os.getpid())
            proc.mkdir(parents=True)
            (proc / 'cmdline').write_bytes(('\0'.join(['$executable'] + sys.argv[1:]) + '\0').encode())
            (root / 'ready').write_text(str(os.getpid()))
            while not (root / 'release').exists():
                time.sleep(0.01)
        """.trimIndent())
        check(executable.toFile().setExecutable(true))
    }

    fun startRecorder(): Process {
        Files.createDirectories(remote)
        return start(listOf(executable.toString(), "--time-limit", "150", "$remote/recording.mp4")).also {
            awaitRecorder()
        }
    }

    fun startUnrelated() = start(listOf("/bin/sleep", "60"))

    fun startWrapper(): Process = start(listOf("/bin/sh", "-c", """
        tr() {
            command tr "${'$'}@"
            printf observed > '$guardRead'
        }
        ${localize(scripts.wrapper)}
    """.trimIndent()))

    fun awaitRecorder(): Long {
        awaitFile(ready)
        return Files.readString(ready).toLong().also { recorderPid = it }
    }

    fun awaitCleanupGuard() = awaitFile(guardRead)

    fun releaseRecorder() { Files.writeString(release, "release") }

    fun commandLine(pid: Long): Path = root.resolve("proc/$pid/cmdline")

    fun writeOwner(pid: Any, token: String = scripts.token) {
        Files.writeString(remote.resolve("owner"), "$token $pid\n")
    }

    fun owner(): String = Files.readString(remote.resolve("owner"))

    fun run(script: String, pid: Long): RecordingCommandResult {
        val allowed = children.joinToString("|") { it.pid().toString() }
        // The sandbox prevents malformed PID regressions from signalling the test runner's process group.
        val safeKill = """
            kill() {
                printf '%s %s\n' "${'$'}1" "${'$'}2" >> '$signalLog'
                case "${'$'}2" in $allowed) /bin/kill "${'$'}@";; *) return 99;; esac
            }
        """.trimIndent()
        return shell("$safeKill\n${localize(script).replace("= 1234 ]", "= $pid ]")}")
    }

    fun signalWrapper(wrapper: Process, signal: String): RecordingCommandResult =
        shell("/bin/kill -$signal ${wrapper.pid()}")

    private fun localize(script: String) = script.removeSurrounding("'").replace("'\\''", "'")
        .replace(scripts.remote, remote.toString())
        .replace("/system/bin/screenrecord", executable.toString())
        .replace("/proc/", "$root/proc/")

    private fun start(command: List<String>): Process = ProcessBuilder(command)
        .redirectOutput(root.resolve("stdout-${children.size}").toFile())
        .redirectError(root.resolve("stderr-${children.size}").toFile())
        .start().also { children += it }

    private fun shell(script: String): RecordingCommandResult {
        val process = ProcessBuilder("/bin/sh", "-c", script).start()
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Local protocol command timed out" }
            return RecordingCommandResult(process.exitValue(), process.inputStream.readBytes(),
                process.errorStream.readBytes().decodeToString())
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }

    private fun awaitFile(path: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!Files.exists(path) || Files.size(path) == 0L) {
            check(System.nanoTime() < deadline) { "Missing protocol checkpoint $path" }
            Thread.sleep(10)
        }
    }

    override fun close() {
        releaseRecorder()
        recorderPid?.let { ProcessHandle.of(it).ifPresent { child ->
            if (child.isAlive) child.destroyForcibly()
        } }
        children.asReversed().forEach {
            if (it.isAlive && !it.waitFor(1, TimeUnit.SECONDS)) it.destroyForcibly()
            check(it.waitFor(5, TimeUnit.SECONDS)) { "Local protocol process was not reaped" }
        }
    }
}
