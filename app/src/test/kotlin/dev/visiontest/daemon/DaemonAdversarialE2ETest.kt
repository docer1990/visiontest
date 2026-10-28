package dev.visiontest.daemon

import dev.visiontest.config.IOSAutomationConfig
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Test-only process entry point: production limits and command surface stay unchanged. */
object AdversarialDaemonHost {
    @JvmStatic fun main(args: Array<String>) {
        val normal = DaemonHost().identity
        val identity = if (args[1] == "mismatch") normal.copy(build = "f".repeat(64)) else normal
        DaemonHost(DaemonRegistry(Path.of(args[0])), identity,
            DaemonHostOptions(idleMs = if (args[1] == "idle") 2000 else 60_000, queueWaitMs = 1500)).use {
            it.start()
            it.awaitStopped()
        }
    }
}

@Tag("e2e")
class DaemonAdversarialE2ETest {
    @TempDir lateinit var directory: Path
    private val runtime get() = directory.resolve("daemon")
    private val java get() = Path.of(System.getProperty("java.home"), "bin/java").toString()
    private val jar get() = System.getProperty("visiontest.jar")
    private data class Output(val code: Int, val stdout: String, val stderr: String)
    private data class Child(val process: Process, val stdout: Path, val stderr: Path)

    private fun start(vararg args: String, cwd: Path = directory, environment: Map<String, String> = emptyMap(),
                      stdin: String = ""): Child {
        val output = Files.createTempFile(directory, "out", ".txt")
        val error = Files.createTempFile(directory, "err", ".txt")
        val builder = ProcessBuilder(java, "-jar", jar, *args).directory(cwd.toFile())
            .redirectOutput(output.toFile()).redirectError(error.toFile())
        builder.environment().putAll(environment)
        builder.environment()["VISIONTEST_DAEMON_DIR"] = runtime.toString()
        builder.environment()["PATH"] = directory.resolve("bin").toString() + ":" + builder.environment()["PATH"]
        return Child(builder.start(), output, error).also {
            it.process.outputStream.use { stream -> stream.write(stdin.toByteArray()) }
        }
    }

    private fun finish(child: Child): Output = try {
        assertTrue(child.process.waitFor(25, TimeUnit.SECONDS), "client timed out: ${Files.readString(child.stderr)}")
        Output(child.process.exitValue(), Files.readString(child.stdout), Files.readString(child.stderr))
    } finally { if (child.process.isAlive) child.process.destroyForcibly() }

    private fun run(vararg args: String) = finish(start(*args))
    private fun status() = run("daemon", "status", "--json").let {
        assertEquals(0, it.code, it.stderr)
        Json.parseToJsonElement(it.stdout).jsonObject
    }
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue(condition(), "condition did not settle")
    }

    private fun host(mode: String): Process {
        val classes = Path.of(AdversarialDaemonHost::class.java.protectionDomain.codeSource.location.toURI())
        val child = ProcessBuilder(java, "-cp", "$classes:$jar",
            AdversarialDaemonHost::class.java.name, runtime.toString(), mode)
            .redirectError(directory.resolve("host.err").toFile())
            .redirectOutput(directory.resolve("host.out").toFile()).start()
        until { Files.exists(runtime.resolve("ready.json")) || !child.isAlive }
        assertTrue(child.isAlive, Files.readString(directory.resolve("host.err")))
        return child
    }

    private fun inventory() {
        val bin = Files.createDirectories(directory.resolve("bin"))
        Files.writeString(directory.resolve("target"), "first")
        val script = bin.resolve("xcrun")
        Files.writeString(script, """
            #!/bin/sh
            if [ -n "${'$'}VISIONTEST_CALLER_TEST" ]; then
                printf '%s' "${'$'}PWD:${'$'}VISIONTEST_CALLER_TEST" > caller.txt
            fi
            id=${'$'}(cat '${directory.resolve("target")}')
            printf '{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-18-0":[{"udid":"%s","name":"Fixture","state":"Booted","isAvailable":true}]}}' "${'$'}id"
        """.trimIndent() + "\n")
        check(script.toFile().setExecutable(true, true))
    }

    private fun backend(dispatch: (RecordedRequest) -> MockResponse): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.method == "GET")
                MockResponse().setBody("OK") else dispatch(request)
        }
        start(InetAddress.getByName("127.0.0.1"), IOSAutomationConfig.DEFAULT_PORT)
    }
    private fun success() = MockResponse().setBody("""{"result":{"success":true,"fixture":"legacy"}}""")

    @Test fun `simultaneous fresh JVM startup converges on one host`() {
        try {
            val children = List(6) { start("get_device_info") }
            children.forEach { assertEquals(2, finish(it).code) }
            val first = status()
            repeat(3) { assertEquals(first["pid"], status()["pid"]) }
            assertEquals("0", first["initializationCount"].toString())
        } finally { run("daemon", "stop") }
    }

    @Test fun `crashed registration is replaced without killing metadata PID`() {
        val owned = host("normal")
        val old = status()["pid"]
        owned.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        assertTrue(Files.exists(runtime.resolve("ready.json")))
        try {
            assertEquals(2, run("get_device_info").code)
            assertNotEquals(old, status()["pid"])
        } finally { run("daemon", "stop") }
    }

    @Test fun `live incompatible handshake rejects execution while preserving control`() {
        val owned = host("mismatch")
        try {
            val response = run("get_device_info")
            assertEquals(1, response.code)
            assertEquals("", response.stdout)
            assertTrue(response.stderr.contains("Incompatible daemon application build"), response.stderr)
            assertTrue(owned.isAlive)
            assertEquals("false", status()["compatible"].toString())
            assertEquals(0, run("daemon", "stop").code)
            assertTrue(owned.waitFor(5, TimeUnit.SECONDS))
        } finally { if (owned.isAlive) owned.destroyForcibly() }
    }

    @Test fun `idle expiry releases registration and subsequent client starts fresh host`() {
        val owned = host("idle")
        val old = status()["pid"]
        try {
            assertTrue(owned.waitFor(8, TimeUnit.SECONDS))
            assertFalse(Files.exists(runtime.resolve("ready.json")))
            assertEquals(2, run("get_device_info").code)
            assertNotEquals(old, status()["pid"])
        } finally { run("daemon", "stop"); if (owned.isAlive) owned.destroyForcibly() }
    }

    @Test fun `two caller cwd environment stdin and trace contexts stay independent`() {
        inventory()
        backend { success() }.use { server ->
            try {
                val sessionIds = mutableSetOf<String>()
                for ((name, stepCount) in listOf("alpha" to 1, "beta" to 2)) {
                    val cwd = Files.createDirectories(directory.resolve(name))
                    val invocation = finish(start("--trace-performance", "trace.jsonl", "flow", "-p", "ios",
                        "--stdin", "--json", cwd = cwd, environment = mapOf("VISIONTEST_CALLER_TEST" to name),
                        stdin = "press_home\n".repeat(stepCount)))
                    assertEquals(0, invocation.code, invocation.stderr)
                    val trace = Files.readString(cwd.resolve("trace.jsonl"))
                    assertTrue(trace.contains("session.end"))
                    val header = Json.parseToJsonElement(trace.lineSequence().first()).jsonObject
                    assertTrue(sessionIds.add(header.getValue("sessionId").jsonPrimitive.content))
                    val report = Json.parseToJsonElement(invocation.stdout).jsonObject
                    assertEquals(stepCount, report.getValue("steps").jsonArray.size)
                    assertEquals("${cwd.toRealPath()}:$name", Files.readString(cwd.resolve("caller.txt")))
                }
                assertEquals(9, server.requestCount)
                assertEquals("1", status()["initializationCount"].toString())
            } finally { run("daemon", "stop") }
        }
    }

    @Test fun `target change closes retained graph while repeated output remains exact`() {
        inventory()
        backend { success() }.use { server ->
            try {
                val expected = run("--standalone", "press_home", "-p", "ios", "--json")
                val baselineRequests = server.requestCount
                repeat(8) { assertEquals(expected, run("press_home", "-p", "ios", "--json")) }
                assertEquals("1", status()["initializationCount"].toString())
                Files.writeString(directory.resolve("target"), "second")
                assertEquals(expected, run("press_home", "-p", "ios", "--json"))
                assertEquals("2", status()["initializationCount"].toString())
                assertEquals(baselineRequests * 10, server.requestCount)
            } finally { run("daemon", "stop") }
        }
    }

    @Test fun `native backend restart uses fresh responses without replaying earlier action`() {
        inventory()
        val mutations = AtomicInteger()
        try {
            backend { mutations.incrementAndGet(); success() }.use {
                assertEquals(0, run("press_home", "-p", "ios", "--json").code)
            }
            backend {
                mutations.incrementAndGet()
                MockResponse().setBody("""{"result":{"success":true,"fixture":"restarted"}}""")
            }.use {
                val response = run("press_home", "-p", "ios", "--json")
                assertEquals(0, response.code, response.stderr)
                assertTrue(response.stdout.contains("restarted"), response.stdout)
            }
            assertEquals(2, mutations.get())
            assertEquals("1", status()["initializationCount"].toString())
        } finally { run("daemon", "stop") }
    }

    @Test fun `queue timeout and disconnected queued client dispatch no native work`() {
        inventory()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mutations = AtomicInteger()
        backend {
            mutations.incrementAndGet()
            entered.countDown()
            release.await(15, TimeUnit.SECONDS)
            success()
        }.use {
            val owned = host("queue")
            val active = start("press_home", "-p", "ios", "--json")
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val disconnected = start("press_home", "-p", "ios", "--json")
                until { status()["queued"].toString() == "1" }
                disconnected.process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                until { status()["queued"].toString() == "0" }
                val expired = run("press_home", "-p", "ios", "--json")
                assertEquals(1, expired.code)
                assertTrue(expired.stderr.contains("queue wait expired"), expired.stderr)
                assertEquals(1, mutations.get())
                release.countDown()
                assertEquals(0, finish(active).code)
                assertEquals(1, mutations.get())
            } finally {
                release.countDown(); run("daemon", "stop")
                if (active.process.isAlive) active.process.destroyForcibly()
                if (owned.isAlive) owned.destroyForcibly()
            }
        }
    }

    @Test fun `lost second flow mutation preserves confirmed step and never replays`() {
        inventory()
        val mutations = AtomicInteger()
        backend {
            if (mutations.incrementAndGet() == 2) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            else success()
        }.use {
            try {
                val flow = run("flow", "-p", "ios", "--step", "press_home", "--step", "press_home", "--json")
                assertEquals(1, flow.code, flow.stderr)
                val report = Json.parseToJsonElement(flow.stdout).jsonObject
                val steps = report.getValue("steps").jsonArray
                assertEquals("step-1", steps[0].jsonObject.getValue("id").jsonPrimitive.content)
                assertEquals("succeeded", steps[0].jsonObject.getValue("status").jsonPrimitive.content)
                assertEquals("confirmed", steps[0].jsonObject.getValue("certainty").jsonPrimitive.content)
                assertEquals("uncertain", steps[1].jsonObject.getValue("status").jsonPrimitive.content)
                assertEquals("unknown", steps[1].jsonObject.getValue("certainty").jsonPrimitive.content)
                assertEquals(2, mutations.get())
                val later = run("press_home", "-p", "ios", "--json")
                assertEquals(1, later.code)
                assertEquals(2, mutations.get())
            } finally { run("daemon", "stop") }
        }
    }

    @Test fun `daemon death after mutation dispatch reports uncertainty with zero replay`() {
        inventory()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mutations = AtomicInteger()
        backend {
            mutations.incrementAndGet()
            entered.countDown()
            release.await(15, TimeUnit.SECONDS)
            success()
        }.use {
            val owned = host("normal")
            val client = start("press_home", "-p", "ios", "--json")
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                owned.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                val response = finish(client)
                assertEquals(1, response.code)
                assertEquals("", response.stdout)
                assertTrue(response.stderr.contains("uncertain"), response.stderr)
                assertEquals(1, mutations.get())
                assertFalse(owned.isAlive)
            } finally {
                release.countDown()
                if (owned.isAlive) owned.destroyForcibly()
                if (client.process.isAlive) client.process.destroyForcibly()
            }
        }
    }
}
