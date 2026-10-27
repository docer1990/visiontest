package dev.visiontest.daemon

import dev.visiontest.config.IOSAutomationConfig
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.InetAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("e2e")
class DaemonE2ETest {
    @TempDir lateinit var directory: Path
    private data class Output(val code: Int, val stdout: String, val stderr: String)

    private var latestClientPid = 0L
    private fun run(
        vararg args: String, classLog: Path? = null, newSession: Boolean = false, stdin: String = "",
    ): Output {
        val stdout = Files.createTempFile(directory, "out", ".txt")
        val stderr = Files.createTempFile(directory, "err", ".txt")
        val command = mutableListOf(Path.of(System.getProperty("java.home"), "bin/java").toString())
        classLog?.let { command.add("-Xlog:class+load=info:file=$it") }
        command.addAll(listOf("-jar", System.getProperty("visiontest.jar"), *args))
        val executable = if (newSession) listOf("/usr/bin/python3", "-c",
            "import os,sys; os.setsid(); os.execv(sys.argv[1], sys.argv[1:])") + command else command
        val builder = ProcessBuilder(executable).directory(directory.toFile())
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
        builder.environment()["VISIONTEST_DAEMON_DIR"] = directory.resolve("daemon").toString()
        builder.environment()["PATH"] = directory.resolve("bin").toString() + ":" + builder.environment()["PATH"]
        val child = builder.start()
        latestClientPid = child.pid()
        try {
            child.outputStream.use { it.write(stdin.toByteArray()) }
            assertTrue(child.waitFor(25, TimeUnit.SECONDS), "client timed out")
            return Output(child.exitValue(), Files.readString(stdout), Files.readString(stderr))
        } finally { if (child.isAlive) child.destroyForcibly() }
    }

    @Test fun `ordinary invocations reuse one host without loading terminal or device graph in client`() {
        try {
            val log = directory.resolve("classes.log")
            val first = run("wait_for_element", "-p", "android", "--timeout", "0", classLog = log)
            assertEquals(2, first.code, first.stderr)
            assertEquals("", first.stdout)
            val before = run("daemon", "status", "--json")
            assertEquals(0, before.code, before.stderr)
            val initial = Json.parseToJsonElement(before.stdout).jsonObject
            assertEquals("0", initial["initializationCount"].toString())
            assertEquals(2, run("get_device_info").code)
            val after = Json.parseToJsonElement(run("daemon", "status", "--json").stdout).jsonObject
            assertEquals(initial["pid"], after["pid"])
            assertEquals("0", after["initializationCount"].toString())
            val classes = Files.readString(log)
            for (name in listOf("com.github.ajalt.clikt", "com.github.ajalt.mordant",
                "dev.visiontest.cli.ComponentHolder", "dev.visiontest.cli.VisionTestCli")) {
                assertFalse(classes.contains(name), "forwarder loaded $name")
            }
        } finally { run("daemon", "stop") }
    }

    @Test fun `local entry points and standalone never start a daemon`() {
        for (args in listOf(arrayOf("--help"), arrayOf("--version"), arrayOf("--standalone", "get_device_info"))) {
            run(*args)
            assertFalse(Files.exists(directory.resolve("daemon/ready.json")))
        }
        val status = run("daemon", "status", "--json")
        assertEquals(0, status.code, status.stderr)
        assertTrue(status.stdout.contains("\"running\":false"))
    }
    @Test fun `two fresh clients reuse one initialized graph while preserving exact native output`() {
        val bin = Files.createDirectories(directory.resolve("bin"))
        val xcrun = bin.resolve("xcrun")
        Files.writeString(xcrun, """
            #!/bin/sh
            printf '%s' '{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-18-0":[{"udid":"fixture-ios","name":"Fixture","state":"Booted","isAvailable":true}]}}'
        """.trimIndent() + "\n")
        check(xcrun.toFile().setExecutable(true, true))
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(
                    if (request.method == "GET") "OK" else """{"result":{"success":true,"extra":"kept"}}""")
            }
            server.start(InetAddress.getByName("127.0.0.1"), IOSAutomationConfig.DEFAULT_PORT)
            try {
                val expected = Output(0, "{\"success\":true,\"extra\":\"kept\"}\n", "")
                assertEquals(expected, run("--standalone", "press_home", "-p", "ios", "--json"))
                val baselineRequests = server.requestCount
                assertEquals(expected, run("press_home", "-p", "ios", "--json"))
                val first = Json.parseToJsonElement(run("daemon", "status", "--json").stdout).jsonObject
                assertEquals(expected, run("press_home", "-p", "ios", "--json"))
                val second = Json.parseToJsonElement(run("daemon", "status", "--json").stdout).jsonObject
                assertEquals(first["pid"], second["pid"])
                assertEquals("1", second["initializationCount"].toString())
                assertTrue(second["reuseCount"].toString().toInt() >= 1)
                assertEquals(baselineRequests * 3, server.requestCount)
            } finally { run("daemon", "stop") }
        }
    }

    @Test fun `daemon survives termination of its launching client process group`() {
        try {
            val started = run("daemon", "start", newSession = true)
            val launcherPid = latestClientPid
            assertEquals(0, started.code, started.stderr)
            val status = Json.parseToJsonElement(run("daemon", "status", "--json").stdout).jsonObject
            val pid = status.getValue("pid").toString()
            val group = ProcessBuilder("/bin/ps", "-o", "pgid=", "-p", pid).start()
                .inputStream.bufferedReader().readText().trim().toLong()
            kotlin.test.assertNotEquals(launcherPid, group, "host inherited caller process group")
            ProcessBuilder("/bin/kill", "-TERM", "-$launcherPid").start().waitFor(2, TimeUnit.SECONDS)
            val alive = run("daemon", "status", "--json")
            assertEquals(0, alive.code, alive.stderr)
            assertEquals(status["pid"], Json.parseToJsonElement(alive.stdout).jsonObject["pid"])
        } finally { run("daemon", "stop") }
    }

    @Test fun `stdin flow validation relays bytes and errors without initializing components`() {
        try {
            val args = arrayOf("flow", "-p", "ios", "--stdin", "--json")
            val standalone = run("--standalone", *args, stdin = "invalid_command\n")
            val daemon = run(*args, stdin = "invalid_command\n")
            assertEquals(2, daemon.code)
            assertEquals(standalone, daemon)
            val status = Json.parseToJsonElement(run("daemon", "status", "--json").stdout).jsonObject
            assertEquals("0", status["initializationCount"].toString())
        } finally { run("daemon", "stop") }
    }

    @Test fun `device help preserves fresh caller PTY dimensions across requests`() {
        val script = directory.resolve("terminal.py")
        Files.writeString(script, """
            import os,pty,fcntl,termios,struct,subprocess,sys,json
            java,jar,runtime=sys.argv[1:]
            env=dict(os.environ,VISIONTEST_DAEMON_DIR=runtime,TERM='xterm-256color')
            env.pop('COLUMNS',None);env.pop('LINES',None)
            last_client_pid=None
            def run(args,width,piped=False):
                global last_client_pid
                master,slave=pty.openpty()
                fcntl.ioctl(slave,termios.TIOCSWINSZ,struct.pack('HHHH',24,width,0,0))
                child=subprocess.Popen([java,'-jar',jar]+args,stdin=subprocess.DEVNULL if piped else slave,
                    stdout=slave,stderr=slave,env=env)
                last_client_pid=child.pid
                os.close(slave);out=b''
                try:
                    while True:
                        try: chunk=os.read(master,65536)
                        except OSError: break
                        if not chunk: break
                        out+=chunk
                    child.wait(timeout=20)
                    return child.returncode,out
                finally:
                    os.close(master)
                    if child.poll() is None: child.kill();child.wait()
            try:
                pid=None
                for width in [40,100]:
                    for piped in [False,True]:
                        for command in ['get_device_info','flow']:
                            args=[command,'--help']
                            expected=run(['--standalone']+args,width,piped)
                            actual=run(args,width,piped)
                            assert expected==actual,dict(width=width,piped=piped,command=command,
                                client_pid=last_client_pid,previous_host_pid=pid,expected_code=expected[0],
                                actual_code=actual[0],expected_output=repr(expected[1]),actual_output=repr(actual[1]))
                    status=run(['daemon','status','--json'],width)
                    current=json.loads(status[1])['pid']
                    if pid is not None: assert current==pid
                    pid=current
            finally: run(['daemon','stop'],80)
        """.trimIndent())
        val stdout = directory.resolve("pty.stdout")
        val stderr = directory.resolve("pty.stderr")
        val child = ProcessBuilder("/usr/bin/python3", script.toString(),
            Path.of(System.getProperty("java.home"), "bin/java").toString(), System.getProperty("visiontest.jar"),
            directory.resolve("daemon").toString())
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        try {
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "PTY probe timed out")
            assertEquals(0, child.exitValue(), Files.readString(stderr))
        } finally { if (child.isAlive) child.destroyForcibly() }
    }

}
