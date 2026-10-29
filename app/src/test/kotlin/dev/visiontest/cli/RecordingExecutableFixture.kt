package dev.visiontest.cli

import dev.visiontest.config.AutomationConfig
import dev.visiontest.config.IOSAutomationConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal data class PackagedRecordingResult(val exitCode: Int, val stdout: String, val stderr: String)

internal class RecordingExecutableFixture(val directory: Path) : AutoCloseable {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = MockWebServer()
    private val adbEndpoint = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1 }
    private var unrelated: Process? = null
    private var child: Process? = null
    private var daemonUsed = false
    private val daemonDirectory = Files.createTempDirectory(Path.of("/tmp"), "vtd-")
    val requestCount get() = server.requestCount

    fun run(platform: Platform, inputMode: String, failure: String?, json: Boolean,
            standalone: Boolean = true): PackagedRecordingResult {
        startServer(platform, failure)
        prepareExecutables()
        unrelated = ProcessBuilder("/bin/sleep", "120").start()
        val steps = listOf("press_home", if (failure == "preflight") "invalid_command" else "press_home")
        val input = directory.resolve("input.flow").also { Files.writeString(it, steps.joinToString("\n")) }
        val source = when (inputMode) {
            "step" -> steps.flatMap { listOf("--step", it) }
            "file" -> listOf("--file", input.toString())
            "stdin" -> listOf("--stdin")
            else -> error("Unknown input mode $inputMode")
        }
        daemonUsed = !standalone
        val builder = ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", System.getProperty("visiontest.jar")) +
            (if (standalone) listOf("--standalone") else emptyList()) +
            listOf("flow", "-p", platform.value, "--record",
            "--timeout", "15000", "--step-timeout", "10000") +
            (if (json) listOf("--json") else emptyList()) + source)
        isolateEnvironment(builder, failure)
        val stdout = directory.resolve("stdout")
        val stderr = directory.resolve("stderr")
        val process = builder.directory(directory.toFile()).redirectInput(input.toFile())
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start().also { child = it }
        assertTrue(process.waitFor(70, TimeUnit.SECONDS), "packaged recording exceeded its 70-second watchdog")
        return PackagedRecordingResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
    }

    fun followup(platform: Platform): PackagedRecordingResult {
        return followupCommand("press_home", "-p", platform.value, "--json")
    }

    fun diagnosticFollowup(platform: Platform): PackagedRecordingResult =
        followupCommand("automation_server_status", "-p", platform.value)

    fun daemonStatus(): kotlinx.serialization.json.JsonObject {
        val result = followupCommand("daemon", "status", "--json")
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
        return Json.parseToJsonElement(result.stdout).jsonObject
    }

    private fun followupCommand(vararg arguments: String): PackagedRecordingResult {
        check(daemonUsed)
        val builder = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", System.getProperty("visiontest.jar"), *arguments)
        isolateEnvironment(builder, null)
        val stdout = directory.resolve("followup.stdout")
        val stderr = directory.resolve("followup.stderr")
        val process = builder.directory(directory.toFile()).redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile()).start().also { child = it }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "daemon followup exceeded its watchdog")
        return PackagedRecordingResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
    }

    fun invocations(): List<String> = directory.resolve("invocations").let {
        if (Files.exists(it)) Files.readAllLines(it) else emptyList()
    }

    fun assertOwnedCleanup(expectInterrupt: Boolean) {
        assertTrue(checkNotNull(unrelated).isAlive, "cleanup terminated an unrelated process")
        assertEquals("unrelated", Files.readString(directory.resolve("unrelated.mp4")))
        recorder()?.let { assertFalse(it.isAlive, "owned recorder survived CLI exit") }
        assertEquals(expectInterrupt, Files.exists(directory.resolve("interrupted")))
        Files.walk(directory).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().startsWith(".recording-") },
                "temporary host recording survived cleanup")
        }
        assertFalse(Files.exists(directory.resolve("remote-video")), "owned Android media was not removed")
        try {
            adbEndpoint.accept().use { error("Recording discovery contacted an ADB socket instead of the host seam") }
        } catch (_: SocketTimeoutException) {
            // No connection is expected. Discovery must use the captured fake executable.
        }
        assertFalse(Files.exists(directory.resolve("unexpected")), "fake executable received an unexpected command")
    }

    private fun startServer(platform: Platform, failure: String?) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setBody("OK")
                calls += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    .getValue("method").jsonPrimitive.content
                check(Files.exists(directory.resolve("ready"))) { "A step began before recording readiness" }
                val response = MockResponse().setBody("""{"result":{"success":true}}""")
                if (failure?.startsWith("premature") == true) {
                    Files.writeString(directory.resolve("exit-gate"), "exit")
                    response.setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                return response
            }
        }
        // Bind before launching the CLI so an occupied port never reaches a real backend.
        try {
            server.start(InetAddress.getByName("127.0.0.1"),
                if (platform == Platform.Android) AutomationConfig.DEFAULT_PORT else IOSAutomationConfig.DEFAULT_PORT)
        } catch (_: BindException) {
            assumeTrue(false, "$platform automation port is occupied; no live server was contacted")
        }
    }

    private fun prepareExecutables() {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "fake recorders require /usr/bin/python3")
        val bin = Files.createDirectories(directory.resolve("sdk/platform-tools"))
        listOf("adb", "xcrun").forEach { name ->
            val executable = bin.resolve(name)
            Files.writeString(executable, "#!/usr/bin/python3\n" + FAKE_EXECUTABLE.trimIndent() + "\n")
            check(executable.toFile().setExecutable(true, true))
        }
        Files.write(directory.resolve("valid-video"), structuralVideo())
        Files.write(directory.resolve("initialized-video"),
            box("ftyp", "isom\u0000\u0000\u0000\u0000isom".toByteArray()) + words(0) + "mdat".toByteArray())
        Files.writeString(directory.resolve("unrelated.mp4"), "unrelated")
    }

    private fun isolateEnvironment(builder: ProcessBuilder, failure: String?) {
        builder.environment().apply {
            clear()
            put("PATH", directory.resolve("sdk/platform-tools").toString())
            put("HOME", directory.toString())
            put("ANDROID_HOME", directory.resolve("sdk").toString())
            put("ANDROID_SDK_ROOT", directory.resolve("sdk").toString())
            put("ANDROID_ADB_SERVER_PORT", adbEndpoint.localPort.toString())
            put("ANDROID_ADB_SERVER_ADDRESS", "127.0.0.1")
            put("ADB_SERVER_SOCKET", "tcp:127.0.0.1:${adbEndpoint.localPort}")
            put("ANDROID_SERIAL", "fixture-android")
            put("RECORDING_FIXTURE", directory.toString())
            put("RECORDING_FAILURE", failure.orEmpty())
            put("VISIONTEST_DAEMON_DIR", daemonDirectory.toString())
        }
    }

    private fun recorder(): ProcessHandle? = directory.resolve("recorder-pid").let {
        if (Files.exists(it)) ProcessHandle.of(Files.readString(it).trim().toLong()).orElse(null) else null
    }

    override fun close() {
        if (daemonUsed) {
            val stop = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", System.getProperty("visiontest.jar"), "daemon", "stop")
            isolateEnvironment(stop, null)
            stop.directory(directory.toFile()).redirectErrorStream(true).start().apply {
                inputStream.close()
                if (!waitFor(10, TimeUnit.SECONDS)) destroyForcibly()
            }
        }
        child?.takeIf { it.isAlive }?.let { process ->
            process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        recorder()?.takeIf { it.isAlive }?.let { process ->
            check(process.info().commandLine().orElse("").contains(directory.toString()))
            process.destroyForcibly()
            process.onExit().get(2, TimeUnit.SECONDS)
        }
        unrelated?.let { it.destroyForcibly(); it.waitFor(2, TimeUnit.SECONDS) }
        adbEndpoint.close()
        server.close()
        Files.walk(daemonDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

private fun words(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).apply {
    values.forEach { putInt(it) }
}.array()

private fun box(type: String, payload: ByteArray): ByteArray = words(payload.size + 8) + type.toByteArray() + payload

private fun structuralVideo(): ByteArray {
    val ftyp = box("ftyp", "isom\u0000\u0000\u0000\u0000isom".toByteArray())
    val visual = ByteBuffer.allocate(78).apply {
        position(6); putShort(1)
        position(24); putShort(16); putShort(16)
        putInt(0x00480000); putInt(0x00480000); putInt(0); putShort(1)
        position(74); putShort(24); putShort(-1)
    }.array()
    val avc = byteArrayOf(1, 66, 0, 30, -1, -31, 0, 5, 103, 66, 0, 30, -128, 1, 0, 2, 104, -128)
    val description = box("stsd", words(0, 1) + box("avc1", visual + box("avcC", avc)))
    val tables = description + box("stsz", words(0, 0, 1, 5)) +
        box("stco", words(0, 1, ftyp.size + 8)) + box("stsc", words(0, 1, 1, 1, 1))
    val reference = box("dinf", box("dref", words(0, 1) + box("url ", words(1))))
    val media = box("mdia", box("hdlr", words(0, 0) + "vide".toByteArray() + words(0, 0, 0)) +
        box("minf", reference + box("stbl", tables)))
    return ftyp + box("mdat", byteArrayOf(0, 0, 0, 1, 101)) + box("moov", box("trak", media))
}

private const val FAKE_EXECUTABLE = """
    import json, os, pathlib, re, shlex, signal, sys, time

    root = pathlib.Path(os.environ['RECORDING_FIXTURE'])
    failure = os.environ['RECORDING_FAILURE']
    args = sys.argv[1:]
    with (root / 'invocations').open('a') as log:
        log.write(json.dumps([pathlib.Path(sys.argv[0]).name] + args) + '\n')

    def unexpected():
        (root / 'unexpected').write_text(repr(args))
        sys.exit(97)

    def record(output, token=None):
        (root / 'recorder-pid').write_text(str(os.getpid()))
        if token:
            (root / 'token').write_text(token)
            print(token, os.getpid(), flush=True)
        def finish(code):
            source = 'initialized-video' if failure == 'invalid-video' else 'valid-video'
            output.write_bytes((root / source).read_bytes())
            (root / 'done').write_text(str(code))
            sys.exit(code)
        def interrupt(number, frame):
            (root / 'interrupted').write_text(str(number))
            finish(0)
        signal.signal(signal.SIGINT, interrupt)
        if failure == 'startup':
            (root / 'done').write_text('9')
            print('fixture recorder startup failed', file=sys.stderr, flush=True)
            sys.exit(9)
        output.write_bytes((root / 'initialized-video').read_bytes())
        (root / 'ready').write_text('ready')
        if not token:
            print('Recording started', file=sys.stderr, flush=True)
        print('private recorder stdout', flush=True)
        print('private recorder stderr', file=sys.stderr, flush=True)
        while True:
            if (root / 'exit-gate').exists():
                finish(7 if failure == 'premature-nonzero' else 0)
            time.sleep(0.01)

    if pathlib.Path(sys.argv[0]).name == 'xcrun':
        if args == ['simctl', 'list', 'devices', 'available', '--json']:
            print(json.dumps({'devices': {'com.apple.CoreSimulator.SimRuntime.iOS-26-5': [
                {'udid': 'fixture-ios', 'name': 'Fixture Simulator', 'state': 'Booted', 'isAvailable': True}
            ]}}))
        elif args[:5] == ['simctl', 'io', 'fixture-ios', 'recordVideo', '--codec=h264']:
            output = pathlib.Path(args[5])
            assert output.is_relative_to(root)
            record(output)
        else:
            unexpected()
        sys.exit(0)

    if args[:4] != ['-H', '127.0.0.1', '-P', os.environ['ANDROID_ADB_SERVER_PORT']]:
        unexpected()
    args = args[4:]
    if args == ['devices']:
        print('List of devices attached\nfixture-android\tdevice\n')
        sys.exit(0)
    if args[:2] != ['-s', 'fixture-android']:
        unexpected()
    args = args[2:]
    if args == ['shell', 'screenrecord', '--help']:
        print('--time-limit TIME Maximum is 180.')
    elif args[:4] == ['shell', '-tt', 'sh', '-c']:
        token = re.search(r'/data/local/tmp/visiontest-([0-9a-f-]+)', args[4]).group(1)
        record(root / 'remote-video', token)
    elif args[:4] == ['shell', '-T', 'sh', '-c']:
        script = shlex.split(args[4])[0]
        token = re.search(r'/data/local/tmp/visiontest-([0-9a-f-]+)', script).group(1)
        assert token == (root / 'token').read_text()
        pid = int((root / 'recorder-pid').read_text())
        if 'dd if=' in script:
            offset = int(re.search(r'skip=(\d+)', script).group(1))
            count = int(re.search(r'count=(\d+)', script).group(1))
            if (root / 'remote-video').exists():
                sys.stdout.buffer.write((root / 'remote-video').read_bytes()[offset:offset + count])
        elif 'kill -INT' in script:
            assert script.endswith('kill -INT "${'$'}pid"')
            assert ('; [ "${'$'}pid" = ' + str(pid) + ' ] || exit 1') in script
            os.kill(pid, signal.SIGINT)
        elif 'rm -f' in script:
            (root / 'remote-video').unlink(missing_ok=True)
        elif 'STOPPED' in script:
            done = root / 'done'
            if done.exists():
                print(token, pid, 'STOPPED', done.read_text())
            else:
                os.kill(pid, 0)
                print(token, pid, 'LIVE')
        else:
            unexpected()
    elif args[0] == 'pull':
        assert args[1] == '/data/local/tmp/visiontest-' + (root / 'token').read_text() + '/recording.mp4'
        destination = pathlib.Path(args[2])
        assert destination.is_relative_to(root)
        destination.write_bytes((root / 'remote-video').read_bytes())
        print('private adb transfer output')
    else:
        unexpected()
"""
