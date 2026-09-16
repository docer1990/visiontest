package com.example.visiontest.cli

import com.example.visiontest.android.Android
import com.example.visiontest.android.AutomationClient
import com.example.visiontest.cli.commands.ClearTextCommand
import com.example.visiontest.cli.commands.DismissKeyboardCommand
import com.example.visiontest.cli.commands.DoubleTapCommand
import com.example.visiontest.cli.commands.HandleAlertCommand
import com.example.visiontest.cli.commands.InputTextCommand
import com.example.visiontest.cli.commands.LongPressCommand
import com.example.visiontest.cli.commands.PressKeyCommand
import com.example.visiontest.common.DeviceConfig
import com.example.visiontest.common.DeviceType
import com.example.visiontest.common.MobileDevice
import com.example.visiontest.discovery.ToolDiscovery
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.ios.IOSManager
import com.example.visiontest.tools.AndroidAutomationToolRegistrar
import com.example.visiontest.tools.AndroidDeviceToolRegistrar
import com.example.visiontest.tools.IOSAutomationToolRegistrar
import com.example.visiontest.tools.IOSDeviceToolRegistrar
import com.github.ajalt.clikt.core.CliktCommand
import com.google.gson.JsonParser
import io.mockk.mockk
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InteractionCliTest {
    private val androidServer = MockWebServer()
    private val iosServer = MockWebServer()
    private var deviceCalls = 0
    private val backend = object : DeviceConfig {
        override suspend fun listDevices() = listOf(getFirstAvailableDevice())
        override suspend fun getFirstAvailableDevice(): MobileDevice {
            deviceCalls++
            return MobileDevice("device-1", "Phone", DeviceType.ANDROID, "device")
        }
        override suspend fun listApps(deviceId: String?) = emptyList<String>()
        override suspend fun getAppInfo(packageName: String, deviceId: String?) = ""
        override suspend fun launchApp(packageName: String, activityName: String?, deviceId: String?) = true
        override suspend fun executeShell(command: String, deviceId: String?) = ""
    }
    private lateinit var components: Lazy<ComponentHolder>

    @BeforeTest
    fun setup() {
        androidServer.start()
        iosServer.start()
        val androidClient = AutomationClient(androidServer.hostName, androidServer.port)
        val iosClient = IOSAutomationClient(iosServer.hostName, iosServer.port)
        val logger = LoggerFactory.getLogger(javaClass)
        val discovery = ToolDiscovery(logger)
        val iosBackend = object : DeviceConfig by backend {
            override suspend fun getFirstAvailableDevice(): MobileDevice {
                deviceCalls++
                return MobileDevice(
                    "simulator-1", "iPhone 17", DeviceType.IOS_SIMULATOR, "Booted", "26.5", "iPhone 17"
                )
            }
        }
        components = lazy {
            ComponentHolder(
                mockk<Android>(), mockk<IOSManager>(), androidClient, iosClient,
                AndroidDeviceToolRegistrar(backend), AndroidAutomationToolRegistrar(backend, androidClient, discovery),
                IOSDeviceToolRegistrar(iosBackend),
                IOSAutomationToolRegistrar(iosBackend, iosClient, discovery, logger)
            )
        }
    }

    @AfterTest
    fun cleanup() {
        androidServer.shutdown()
        iosServer.shutdown()
    }

    private fun invoke(
        factory: (Lazy<ComponentHolder>, CliCommandRunner) -> CliktCommand,
        vararg args: String
    ): CliResult {
        var result: CliResult? = null
        val parsed = executeCliCommand {
            factory(components) { result = executeCliCommand(it) }.parse(args.toList())
            ""
        }
        return result ?: parsed
    }

    private fun respond(server: MockWebServer, result: String) {
        server.enqueue(MockResponse().setBody("OK"))
        server.enqueue(MockResponse().setBody("OK"))
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":17,"result":$result}"""))
    }

    private fun invokeInteraction(name: String, vararg args: String): CliResult = invoke(
        when (name) {
            "press_key" -> ::PressKeyCommand
            "clear_text" -> ::ClearTextCommand
            "long_press" -> ::LongPressCommand
            "double_tap" -> ::DoubleTapCommand
            "dismiss_keyboard" -> ::DismissKeyboardCommand
            "handle_alert" -> ::HandleAlertCommand
            else -> error("Unknown interaction command: $name")
        },
        *args,
    )

    @Test
    fun `interaction commands require a valid explicit platform`() {
        val commands = listOf("press_key", "clear_text", "long_press", "double_tap", "dismiss_keyboard", "handle_alert")
        for (name in commands) {
            assertEquals(2, invokeInteraction(name).exitCode, name)
            assertEquals(2, invokeInteraction(name, "-p", "windows").exitCode, name)
        }
        assertFalse(components.isInitialized())
    }

    @Test
    fun `platform specific interactions reject the other platform before initialization`() {
        val cases = listOf(
            "press_key" to arrayOf("-p", "ios", "enter"),
            "clear_text" to arrayOf("-p", "ios"),
            "dismiss_keyboard" to arrayOf("-p", "android"),
            "handle_alert" to arrayOf("-p", "android", "accept"),
        )
        for ((name, args) in cases) assertEquals(5, invokeInteraction(name, *args).exitCode, name)
        assertFalse(components.isInitialized())
        assertEquals(0, androidServer.requestCount)
        assertEquals(0, iosServer.requestCount)
    }

    @Test
    fun `press key adapts unsigned numeric keys and all named actions`() {
        for (key in listOf("0", "066", "2147483647", "enter", "tab", "backspace", "delete", "escape", "ENTER")) {
            respond(androidServer, """{"success":true}""")
            val result = invokeInteraction("press_key", "-p", "android", key)
            assertEquals(0, result.exitCode, result.stderr)
            val wire = request(androidServer)
            assertEquals("ui.pressKey", wire["method"].asString)
            val expected = if (key.toIntOrNull() != null) """{"keyCode":${key.toInt()}}"""
                else """{"action":"${key.lowercase()}"}"""
            assertEquals(JsonParser.parseString(expected), wire["params"])
        }
    }

    @Test
    fun `focused clear and ios keyboard dismissal delegate to native methods`() {
        respond(androidServer, """{"success":true}""")
        assertEquals(0, invokeInteraction("clear_text", "-p", "android").exitCode)
        val clear = request(androidServer)
        assertEquals("ui.clearText", clear["method"].asString)
        assertEquals("{}", clear["params"].toString())
        for (scope in listOf(emptyArray(), arrayOf("--bundle-id", "app.id"))) {
            respond(iosServer, """{"success":true}""")
            assertEquals(0, invokeInteraction("dismiss_keyboard", "-p", "ios", *scope).exitCode)
            val wire = request(iosServer)
            assertEquals("ui.dismissKeyboard", wire["method"].asString)
            val expected = if (scope.isEmpty()) "{}" else """{"bundleId":"app.id"}"""
            assertEquals(JsonParser.parseString(expected), wire["params"])
        }
    }

    @Test
    fun `alert action label and app scope reach the ios registrar`() {
        for (action in listOf("accept", "dismiss")) {
            for (options in listOf(emptyArray(), arrayOf("--button-label", "Allow", "--bundle-id", "app.id"))) {
                respond(iosServer, """{"success":true}""")
                val result = invokeInteraction("handle_alert", "-p", "ios", action, *options)
                assertEquals(0, result.exitCode, result.stderr)
                val wire = request(iosServer)
                assertEquals("ui.handleAlert", wire["method"].asString)
                val expected = JsonParser.parseString("""{"action":"$action"}""").asJsonObject
                if (options.isNotEmpty()) {
                    expected.addProperty("buttonLabel", "Allow")
                    expected.addProperty("bundleId", "app.id")
                }
                assertEquals(expected, wire["params"])
            }
        }
    }

    @Test
    fun `gestures forward coordinates on each platform without a timeout`() {
        for ((name, method) in listOf("long_press" to "ui.longPress", "double_tap" to "ui.doubleTap")) {
            for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
                respond(server, """{"success":true}""")
                val result = invokeInteraction(name, "-p", platform, "--x", "0", "--y", "20")
                assertEquals(0, result.exitCode, result.stderr)
                val wire = request(server)
                assertEquals(method, wire["method"].asString)
                assertEquals(JsonParser.parseString("""{"x":0,"y":20}"""), wire["params"])
            }
        }
    }

    @Test
    fun `gestures forward all selectors scope and explicit or default timeout`() {
        for ((name, method) in listOf("long_press" to "ui.longPress", "double_tap" to "ui.doubleTap")) {
            for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
                for (timeout in listOf(null, 1, 30_000)) {
                    assertSelectorGesture(name, method, platform, server, timeout)
                }
            }
        }
    }

    private fun assertSelectorGesture(
        name: String,
        method: String,
        platform: String,
        server: MockWebServer,
        timeout: Int?,
    ) {
        respond(server, """{"success":true}""")
        val args = mutableListOf(
            "-p", platform, "--text", "exact", "--text-contains", "partial", "--resource-id", "field",
            "--class-name", "Button", "--content-description", "label",
        )
        if (platform == "ios") args += listOf("--bundle-id", "app.id")
        if (timeout != null) args += listOf("--timeout", timeout.toString())
        val result = invokeInteraction(name, *args.toTypedArray())
        assertEquals(0, result.exitCode, result.stderr)
        val wire = request(server)
        assertEquals(method, wire["method"].asString)
        val expected = JsonParser.parseString(
            """{"text":"exact","textContains":"partial","resourceId":"field","className":"Button",
                "contentDescription":"label","timeoutMs":${timeout ?: 10_000}}"""
        ).asJsonObject
        if (platform == "ios") expected.addProperty("bundleId", "app.id")
        assertEquals(expected, wire["params"])
    }

    @Test
    fun `invalid interaction arguments fail before components or backend access`() {
        for (key in listOf("-1", "+1", "1.0", "2147483648", "1e2", "", " ", "unknown")) {
            assertEquals(2, invokeInteraction("press_key", "-p", "android", "--", key).exitCode, key)
        }
        for (args in listOf(arrayOf("approve"), arrayOf("accept", "--button-label", " "),
            arrayOf("dismiss", "--bundle-id", ""))) {
            assertEquals(2, invokeInteraction("handle_alert", "-p", "ios", *args).exitCode)
        }
        assertEquals(2, invokeInteraction("dismiss_keyboard", "-p", "ios", "--bundle-id", " ").exitCode)
        for (name in listOf("long_press", "double_tap")) {
            for (platform in listOf("android", "ios")) {
                val invalid = listOf(
                    emptyArray(), arrayOf("--x", "1"), arrayOf("--y", "2"),
                    arrayOf("--x", "1", "--y", "2", "--text", "a"),
                    arrayOf("--x", "-1", "--y", "2"), arrayOf("--x", "a", "--y", "2"),
                    arrayOf("--x", "1", "--y", "2", "--timeout", "1"),
                    arrayOf("--x", "1", "--y", "2", "--bundle-id", "app.id"),
                    arrayOf("--bundle-id", "app.id"), arrayOf("--text", " "),
                    arrayOf("--text", "a", "--resource-id", ""),
                    arrayOf("--text", "a", "--timeout", "0"), arrayOf("--text", "a", "--timeout", "30001"),
                    arrayOf("--text", "a", "--timeout", "bad"), arrayOf("--text", "a", "--bundle-id", " "),
                )
                for (args in invalid) {
                    assertEquals(2, invokeInteraction(name, "-p", platform, *args).exitCode, "$name ${args.toList()}")
                }
            }
            assertEquals(2, invokeInteraction(name, "-p", "android", "--text", "a", "--bundle-id", "app.id").exitCode)
        }
        assertFalse(components.isInitialized())
        assertEquals(0, androidServer.requestCount)
        assertEquals(0, iosServer.requestCount)
        assertEquals(0, deviceCalls)
    }

    private fun request(server: MockWebServer): com.google.gson.JsonObject {
        assertEquals("/health", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        assertEquals("/health", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        return JsonParser.parseString(request.body.readUtf8()).asJsonObject
    }

    @Test
    fun `targeted input forwards every prefixed selector on both platforms`() {
        for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
            respond(server, """{"success":true}""")
            val args = mutableListOf(
                "-p", platform, "hello", "--target-text", "exact", "--target-text-contains", "partial",
                "--target-resource-id", "field", "--target-class-name", "EditText",
                "--target-content-description", "label", "--timeout", "1234",
            )
            if (platform == "ios") args += listOf("--bundle-id", "app.id")
            val result = invoke(::InputTextCommand, *args.toTypedArray())
            assertEquals(0, result.exitCode, result.stderr)
            val wire = request(server)
            assertEquals("ui.inputText", wire["method"].asString)
            val params = wire["params"].asJsonObject
            val expected = """{"text":"hello","targetText":"exact","targetTextContains":"partial",
                "targetResourceId":"field","targetClassName":"EditText","targetContentDescription":"label",
                "timeoutMs":1234}"""
            val expectedParams = JsonParser.parseString(expected).asJsonObject
            if (platform == "ios") expectedParams.addProperty("bundleId", "app.id")
            assertEquals(expectedParams, params)
        }
    }

    @Test
    fun `targeted input defaults timeout and permits empty text to focus a field`() {
        for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
            respond(server, """{"success":true}""")
            val result = invoke(::InputTextCommand, "-p", platform, "", "--target-resource-id", "field")
            assertEquals(0, result.exitCode, result.stderr)
            val params = request(server)["params"].asJsonObject
            assertEquals("", params["text"].asString)
            assertEquals(10_000, params["timeoutMs"].asInt)
        }
    }

    @Test
    fun `invalid targeted input fails before component initialization`() {
        for (platform in listOf("android", "ios")) {
            val cases = listOf(
                arrayOf("--timeout", "1234"),
                arrayOf("--target-text", " "),
                arrayOf("--target-text", "a", "--target-resource-id", ""),
                arrayOf("--target-text", "a", "--timeout", "0"),
                arrayOf("--target-text", "a", "--timeout", "30001"),
                arrayOf("--target-text", "a", "--timeout", "invalid"),
            )
            for (args in cases) {
                assertEquals(2, invoke(::InputTextCommand, "-p", platform, "hello", *args).exitCode)
            }
        }
        assertFalse(components.isInitialized())
        assertEquals(0, androidServer.requestCount)
        assertEquals(0, iosServer.requestCount)
    }

    @Test
    fun `untargeted input preserves legacy request shape on both platforms`() {
        for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
            respond(server, """{"success":true}""")
            assertEquals(0, invoke(::InputTextCommand, "-p", platform, "hello").exitCode)
            assertEquals(JsonParser.parseString("""{"text":"hello"}"""), request(server)["params"])
        }
    }

    @Test
    fun `interaction operation failures retain returned output and success exit code`() {
        val cases = listOf(
            "press_key" to arrayOf("enter"), "clear_text" to emptyArray(),
            "long_press" to arrayOf("--text", "missing"), "double_tap" to arrayOf("--x", "1", "--y", "2"),
            "dismiss_keyboard" to emptyArray(), "handle_alert" to arrayOf("dismiss"),
        )
        for ((name, args) in cases) {
            val platform = if (name in listOf("dismiss_keyboard", "handle_alert")) "ios" else "android"
            val server = if (platform == "ios") iosServer else androidServer
            respond(server, """{"success":false,"error":"Native interaction failed"}""")
            val result = invokeInteraction(name, "-p", platform, *args)
            val expected = """{"jsonrpc":"2.0","id":17,"result":""" +
                """{"success":false,"error":"Native interaction failed"}}"""
            assertEquals(CliResult(0, expected, null), result, name)
            request(server)
        }
    }

    @Test
    fun `interactions return exit three when health check fails`() {
        val cases = listOf(
            "press_key" to arrayOf("enter"), "clear_text" to emptyArray(),
            "long_press" to arrayOf("--text", "field"), "double_tap" to arrayOf("--x", "1", "--y", "2"),
            "dismiss_keyboard" to emptyArray(), "handle_alert" to arrayOf("accept"),
        )
        for ((name, args) in cases) {
            val platform = if (name in listOf("dismiss_keyboard", "handle_alert")) "ios" else "android"
            val server = if (platform == "ios") iosServer else androidServer
            server.enqueue(MockResponse().setResponseCode(500))
            val result = invokeInteraction(name, "-p", platform, *args)
            assertEquals(3, result.exitCode, name)
            assertNull(result.stdout)
            assertNotNull(result.stderr)
            assertEquals("/health", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        }
    }

}
