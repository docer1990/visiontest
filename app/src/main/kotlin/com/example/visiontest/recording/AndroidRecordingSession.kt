package com.example.visiontest.recording

import com.example.visiontest.NoDeviceAvailableException
import com.example.visiontest.android.parseAdbDevices
import com.example.visiontest.common.RequestDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidRecordingSession(
    private val scope: CoroutineScope,
    private val artifacts: Path,
    private val requiredDurationMs: Long,
    private val processes: RecordingProcessRunner,
    private val inspector: RecordingContainerInspector = RecordingContainerInspector(),
) : RecordingSession {
    override val terminal = CompletableDeferred<RecordingExit>()
    private var remote: AndroidRemoteRecorder? = null
    private var process: OwnedRecordingProcess? = null
    private var temporary: Path? = null
    private val stopping = AtomicBoolean(false)
    private val finalization = Mutex()
    private var outcome: Result<Path>? = null
    private var launchAttempted = false

    override suspend fun start(deadline: RequestDeadline) = recordingOperation {
        val target = resolveTarget(deadline)
        val help = bounded(deadline) { processes.run(target + listOf("shell", "screenrecord", "--help"), deadline) }
        if (help.exitCode != 0) {
            throw RecordingException("Unable to inspect Android recorder capabilities: ${help.stderr}")
        }
        val duration = AndroidRecordingCapabilities.parse(help.stdout.decodeToString() + "\n" + help.stderr)
            .durationSeconds(requiredDurationMs)
        val recorder = AndroidRemoteRecorder(target, duration, processes).also { remote = it }
        withContext(Dispatchers.IO) {
            deadline.remainingMillis()
            Files.createDirectories(artifacts)
            temporary = Files.createTempFile(artifacts.toAbsolutePath(), ".recording-", ".mp4")
        }
        launchAttempted = true
        val handle = processes.start(scope, recorder.command, deadline).also { process = it }
        observeExit(handle)
        awaitReady(handle, recorder, deadline)
    }

    private suspend fun resolveTarget(deadline: RequestDeadline): List<String> {
        val endpoint = AndroidRecordingEndpoint.capture(System::getProperty, System::getenv)
        val resolved = bounded(deadline) { processes.run(listOf("/bin/sh", "-c", "command -v adb"), deadline) }
        val executable = resolved.stdout.decodeToString().trim()
        if (resolved.exitCode != 0 || executable.isEmpty() || executable.contains('\n')) {
            throw RecordingException("Unable to resolve adb executable on PATH")
        }
        val adb = listOf(Path.of(executable).toAbsolutePath().normalize().toString(),
            "-H", "127.0.0.1", "-P", endpoint.port.toString())
        val devices = bounded(deadline) { processes.run(adb + "devices", deadline) }
        if (devices.exitCode != 0) throw RecordingException("Unable to discover Android devices: ${devices.stderr}")
        val serial = parseAdbDevices(devices.stdout.decodeToString()).first().id
        endpoint.validateSerial(serial)
        return adb + listOf("-s", serial)
    }

    private suspend fun awaitReady(
        handle: OwnedRecordingProcess,
        recorder: AndroidRemoteRecorder,
        deadline: RequestDeadline,
    ) = bounded(deadline) {
        val notification = select {
            handle.exit.onAwait {
                throw RecordingException("Android recorder exited before ownership: ${recordingDiagnostics(it)}")
            }
            handle.firstStdoutLine.onAwait { it }
        }
        recorder.acceptOwner(notification)
        ensureLive(handle, recorder.status(deadline))
        while (!inspector.initialized(recorder, deadline)) {
            ensureLive(handle)
            delay(ANDROID_POLL_MS)
        }
        ensureLive(handle, recorder.status(deadline))
    }

    override suspend fun finish(deadline: RequestDeadline): Path = withContext(NonCancellable) {
        finalization.withLock {
            outcome?.let { return@withLock it.getOrThrow() }
            var result = runCatching { recordingOperation { finalizeVideo(deadline) } }
            val cleanup = runCatching { recordingOperation { cleanup(deadline) } }.exceptionOrNull()
            if (cleanup != null) {
                val failure = result.exceptionOrNull()
                if (failure != null) failure.addSuppressed(cleanup) else result = Result.failure(cleanup)
            }
            outcome = result
            result.getOrThrow()
        }
    }

    private suspend fun finalizeVideo(deadline: RequestDeadline): Path {
        val handle = process ?: throw RecordingException("Android recorder did not start")
        val recorder = checkNotNull(remote)
        val exitedBeforeStop = !handle.isAlive() || handle.exit.isCompleted
        stopping.set(true)
        val premature = exitedBeforeStop || !handle.isAlive() || handle.exit.isCompleted
        val exit = stop(handle, recorder, premature, deadline)
        if (premature) terminal.complete(RecordingExit(exit.exitCode, recordingDiagnostics(exit)))
        val path = checkNotNull(temporary)
        transfer(recorder, path, deadline)
        recorder.remove(deadline)
        inspector.validateVideo(path, deadline)
        return withContext(Dispatchers.IO) {
            deadline.remainingMillis()
            Files.move(path, artifacts.toAbsolutePath().resolve("recording.mp4"))
        }
    }

    private suspend fun stop(
        handle: OwnedRecordingProcess,
        recorder: AndroidRemoteRecorder,
        premature: Boolean,
        deadline: RequestDeadline,
    ): RecordingCommandResult {
        val remaining = deadline.remainingMillis()
        val budget = remaining - minOf(CLEANUP_RESERVE_MS, remaining / CLEANUP_RESERVE_FRACTION)
        return withTimeoutOrNull(budget) {
            if (recorder.status(deadline).live) recorder.signal("INT", deadline)
            val stopped = recorder.awaitStopped(deadline)
            val result = handle.exit.await()
            if (!premature && (stopped.code != 0 || result.exitCode != 0)) {
                throw RecordingException("Android recorder failed to finalize: ${recordingDiagnostics(result)}")
            }
            result
        } ?: throw RecordingException("Android recorder did not stop before the finalization deadline")
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun transfer(recorder: AndroidRemoteRecorder, path: Path, deadline: RequestDeadline) {
        try {
            val remaining = deadline.remainingMillis()
            val budget = remaining - minOf(CLEANUP_RESERVE_MS, remaining / CLEANUP_RESERVE_FRACTION)
            val result = withTimeoutOrNull(budget) {
                processes.run(recorder.target + listOf("pull", recorder.media, path.toString()), deadline)
            } ?: throw RecordingException("Android recording transfer deadline exceeded")
            check(result.exitCode == 0) { result.stderr }
        } catch (failure: Exception) {
            throw RecordingException("Unable to pull Android recording; retained ${recorder.media} on " +
                "${recorder.target.last()}: ${failure.message}", failure)
        }
    }

    private suspend fun cleanup(deadline: RequestDeadline) {
        try {
            try {
                if (launchAttempted) {
                    val remaining = deadline.remainingMillis()
                    val budget = (remaining - remaining / CLEANUP_RESERVE_FRACTION).coerceAtLeast(1)
                    withTimeoutOrNull(budget) { remote?.cleanup(deadline); true }
                        ?: throw RecordingException("Android remote cleanup deadline exceeded")
                }
            } finally {
                process?.takeIf { it.isAlive() }?.let { handle -> bounded(deadline) { handle.forceStop(deadline) } }
            }
        } finally {
            withContext(Dispatchers.IO) {
                temporary?.let { deadline.remainingMillis(); Files.deleteIfExists(it) }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun observeExit(handle: OwnedRecordingProcess) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = try {
                val exit = handle.exit.await()
                RecordingExit(exit.exitCode, recordingDiagnostics(exit))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                RecordingExit(null, failure.message ?: "Unable to observe Android recorder exit")
            }
            if (!stopping.get()) terminal.complete(result)
        }
    }
}

private class AndroidRemoteRecorder(
    val target: List<String>,
    private val duration: Long,
    private val processes: RecordingProcessRunner,
) : RecordingDataSource {
    private val token = UUID.randomUUID().toString()
    private val directory = "/data/local/tmp/visiontest-$token"
    val media = "$directory/recording.mp4"
    private var pid: Long? = null
    private var stopped = false
    private var stopAttempted = false
    // A PTY delivers SIGHUP to the wrapper when the ADB transport closes.
    val command get() = target + listOf("shell", "-tt", "sh", "-c", quote(wrapper))

    override suspend fun read(offset: Long, maxBytes: Int, deadline: RequestDeadline): ByteArray = shell(
        "if [ -f $media ]; then dd if=$media bs=1 skip=$offset count=$maxBytes 2>/dev/null; fi",
        deadline, maxBytes
    ).stdout

    fun acceptOwner(notification: String) {
        pid = parseOwner(notification)
    }

    suspend fun status(deadline: RequestDeadline): RemoteState {
        val line = shell(ownerGuard + "; if [ -s $directory/done ]; then " +
            "printf '$token %s STOPPED %s\\n' \"\$pid\" \"\$(cat $directory/done)\"; " +
            "else cmdline=\$(tr '\\000' ' ' < /proc/\$pid/cmdline 2>/dev/null); " +
            "if [ \"\$cmdline\" = '$processCommandLine' ]; then printf '$token %s LIVE\\n' \"\$pid\"; " +
            "elif [ -z \"\$cmdline\" ]; then printf '$token %s FINALIZING\\n' \"\$pid\"; else exit 1; fi; fi", deadline,
            recoverOwner = pid == null).stdout.decodeToString().trim()
        val match = Regex("($token [1-9][0-9]*) (LIVE|FINALIZING|STOPPED ([0-9]{1,3}))").matchEntire(line)
            ?: throw RecordingException("Invalid Android recorder ownership status")
        val ownedPid = parseOwner(match.groupValues[1])
        if (pid != null && pid != ownedPid) throw RecordingException("Android recorder PID changed")
        pid = ownedPid
        return RemoteState(match.groupValues[2] == "LIVE", match.groupValues[STATUS_CODE_GROUP].toIntOrNull())
    }

    suspend fun signal(signal: String, deadline: RequestDeadline) {
        stopAttempted = true
        shell("$ownerGuard; $liveGuard; kill -$signal \"\$pid\"", deadline)
    }

    suspend fun awaitStopped(deadline: RequestDeadline): RemoteState {
        var state = status(deadline)
        while (state.code == null) { delay(ANDROID_POLL_MS); state = status(deadline) }
        stopped = true
        return state
    }

    suspend fun remove(deadline: RequestDeadline) {
        shell("$ownerGuard; rm -f $media $directory/owner $directory/done $directory/done.tmp " +
            "$directory/log; rmdir $directory", deadline)
    }

    suspend fun cleanup(deadline: RequestDeadline) {
        if (!stopped && status(deadline).live) {
            if (!stopAttempted) runCatching {
                withTimeoutOrNull((deadline.remainingMillis() / 2).coerceAtLeast(1)) {
                    signal("INT", deadline)
                    awaitStopped(deadline)
                }
            }
            if (!stopped) {
                signal("KILL", deadline)
                bounded(deadline) { awaitStopped(deadline) }
            }
        }
    }

    private fun parseOwner(line: String): Long {
        val match = Regex("$token ([1-9][0-9]*)").matchEntire(line.trim())
            ?: throw RecordingException("Invalid Android recorder ownership notification")
        return match.groupValues[1].toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }
            ?: throw RecordingException("Invalid Android recorder PID")
    }

    private val ownerGuard get() =
        "read owner pid < $directory/owner || exit 1; [ \"\$owner\" = $token ] || exit 1; " +
            "case \"\$pid\" in ''|*[!0-9]*) exit 1;; esac; [ \"\$pid\" -gt 0 ] || exit 1" +
            (pid?.let { "; [ \"\$pid\" = $it ] || exit 1" } ?: "")

    private val processCommandLine get() = "/system/bin/screenrecord --time-limit $duration $media "

    private val liveGuard get() =
        "[ \"\$(tr '\\000' ' ' < /proc/\$pid/cmdline)\" = '$processCommandLine' ] || exit 1"

    private val wrapper get() = """
        umask 077
        mkdir $directory || exit 1
        child=
        publish_completion() {
            printf '%s\n' "${'$'}1" > $directory/done.tmp && mv $directory/done.tmp $directory/done
        }
        cleanup() {
            trap '' HUP INT TERM
            child=${'$'}{child:-${'$'}!}
            if [ -n "${'$'}child" ]; then
                printf '$token %s\n' "${'$'}child" > $directory/owner
                pid=${'$'}child
                if ( $liveGuard ); then kill -INT "${'$'}child" 2>/dev/null; fi
                wait "${'$'}child"
                publish_completion "${'$'}?"
            fi
        }
        trap 'cleanup; exit' HUP INT TERM
        /system/bin/screenrecord --time-limit $duration $media > $directory/log 2>&1 &
        child=${'$'}!
        printf '$token %s\n' "${'$'}child" > $directory/owner
        printf '$token %s\n' "${'$'}child"
        wait "${'$'}child"
        code=${'$'}?
        publish_completion "${'$'}code"
        cat $directory/log >&2
        exit "${'$'}code"
    """.trimIndent()

    private suspend fun shell(
        script: String,
        deadline: RequestDeadline,
        limit: Int = 1_048_576,
        recoverOwner: Boolean = false,
    ): RecordingCommandResult {
        val result = bounded(deadline) {
            val command = target + listOf("shell", "-T", "sh", "-c", quote(script))
            var response = processes.run(command, deadline, limit)
            while (recoverOwner && response.exitCode != 0) {
                delay(ANDROID_POLL_MS)
                response = processes.run(command, deadline, limit)
            }
            response
        }
        if (result.exitCode != 0) {
            throw RecordingException("Android recording command failed on ${target.last()}: ${result.stderr}")
        }
        return result
    }
}

private data class RemoteState(val live: Boolean, val code: Int?)

internal class AndroidRecordingEndpoint private constructor(
    val port: Int,
    private val requestedSerial: String?,
) {
    fun validateSerial(serial: String) {
        if (requestedSerial != null && requestedSerial != serial) {
            throw RecordingException("ANDROID_SERIAL $requestedSerial contradicts " +
                "selected Android recording target $serial")
        }
    }

    companion object {
        private const val DEFAULT_PORT = 5037
        private const val PORT_LIMIT = 65535

        fun capture(property: (String) -> String?, environment: (String) -> String?): AndroidRecordingEndpoint {
            val port = decodePort(property("ANDROID_ADB_SERVER_PORT"))
                ?: decodePort(environment("ANDROID_ADB_SERVER_PORT")) ?: DEFAULT_PORT
            val host = environment("ANDROID_ADB_SERVER_ADDRESS")
            val socket = environment("ADB_SERVER_SOCKET")
            val validHost = host == null || host in setOf("127.0.0.1", "localhost")
            val validSocket = socket == null ||
                socket in setOf("tcp:$port", "tcp:127.0.0.1:$port", "tcp:localhost:$port")
            if (!validHost || !validSocket) {
                throw RecordingException("ADB endpoint override contradicts the automation backend at 127.0.0.1:$port")
            }
            return AndroidRecordingEndpoint(port, environment("ANDROID_SERIAL"))
        }

        private fun decodePort(value: String?): Int? = try {
            value?.let { Integer.decode(it) }?.takeIf { it > 0 && it < PORT_LIMIT }
        } catch (_: NumberFormatException) {
            null
        }
    }
}

private fun ensureLive(handle: OwnedRecordingProcess, remote: RemoteState = RemoteState(true, null)) {
    if (!remote.live || !handle.isAlive() || handle.exit.isCompleted) {
        throw RecordingException("Android recorder is not running")
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun <T> recordingOperation(block: suspend () -> T): T = try {
    block()
} catch (failure: Exception) {
    throw when (failure) {
        is CancellationException, is NoDeviceAvailableException, is RecordingException -> failure
        else -> RecordingException("Android recording failed: ${failure.message}", failure)
    }
}

private suspend fun <T : Any> bounded(deadline: RequestDeadline, block: suspend () -> T): T =
    withTimeoutOrNull(deadline.remainingMillis()) { block() }
        ?: throw RecordingException("Android recording deadline exceeded")

private fun quote(script: String) = "'" + script.replace("'", "'\\''") + "'"

private fun recordingDiagnostics(result: RecordingCommandResult): String =
    listOf(result.stderr, result.stdout.decodeToString()).filter { it.isNotBlank() }
        .joinToString("\n").take(DIAGNOSTIC_LIMIT)

private const val ANDROID_POLL_MS = 50L
private const val CLEANUP_RESERVE_MS = 1000L
private const val CLEANUP_RESERVE_FRACTION = 5
private const val STATUS_CODE_GROUP = 3
private const val DIAGNOSTIC_LIMIT = 65_536
