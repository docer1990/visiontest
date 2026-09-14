package com.example.visiontest.cli

import com.example.visiontest.NoDeviceAvailableException
import com.example.visiontest.android.Android
import com.example.visiontest.android.AutomationClient
import com.example.visiontest.cli.commands.AvailableDeviceCommand
import com.example.visiontest.cli.commands.ClearTextCommand
import com.example.visiontest.cli.commands.DismissKeyboardCommand
import com.example.visiontest.cli.commands.DoubleTapCommand
import com.example.visiontest.cli.commands.FindElementCommand
import com.example.visiontest.cli.commands.GetDeviceInfoCommand
import com.example.visiontest.cli.commands.GetInteractiveElementsCommand
import com.example.visiontest.cli.commands.GetUiHierarchyCommand
import com.example.visiontest.cli.commands.HandleAlertCommand
import com.example.visiontest.cli.commands.InfoAppCommand
import com.example.visiontest.cli.commands.InputTextCommand
import com.example.visiontest.cli.commands.ListAppsCommand
import com.example.visiontest.cli.commands.LongPressCommand
import com.example.visiontest.cli.commands.PressKeyCommand
import com.example.visiontest.cli.commands.SwipeCommand
import com.example.visiontest.cli.commands.SwipeOnElementCommand
import com.example.visiontest.cli.commands.TapOnElementCommand
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
import kotlin.test.assertTrue

class ParityCliTest {
    private val androidServer = MockWebServer()
    private val iosServer = MockWebServer()
    private var deviceCalls = 0
    private var apps = listOf("app.\"quoted", "app.two")
    private var deviceFailure = false
    private val backend = object : DeviceConfig {
        override suspend fun listDevices() = listOf(getFirstAvailableDevice())
        override suspend fun getFirstAvailableDevice(): MobileDevice {
            deviceCalls++
            if (deviceFailure) throw NoDeviceAvailableException("No device")
            return MobileDevice("device-1", "Phone", DeviceType.ANDROID, "device")
        }
        override suspend fun listApps(deviceId: String?): List<String> {
            getFirstAvailableDevice()
            return apps
        }
        override suspend fun getAppInfo(packageName: String, deviceId: String?): String {
            getFirstAvailableDevice()
            return "details for $packageName\nversionName=1.0"
        }
        override suspend fun launchApp(packageName: String, activityName: String?, deviceId: String?) = true
        override suspend fun executeShell(command: String, deviceId: String?) =
            "[ro.product.model]: [Pixel]\n[ro.build.version.release]: [15]\n[ro.build.version.sdk]: [35]"
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
                if (deviceFailure) throw NoDeviceAvailableException("No simulator")
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
        for (name in listOf("press_key", "clear_text", "long_press", "double_tap", "dismiss_keyboard", "handle_alert")) {
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
            }
        }
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
            val expected = """{"jsonrpc":"2.0","id":17,"result":{"success":false,"error":"Native interaction failed"}}"""
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

    @Test
    fun `inspection json rejects incomplete results on both platforms`() {
        for (factory in listOf(::GetDeviceInfoCommand, ::GetInteractiveElementsCommand, ::FindElementCommand)) {
            for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
                respond(server, """{"success":true}""")
                val args = mutableListOf("-p", platform, "--json")
                if (factory == ::FindElementCommand) args += listOf("--text", "Login")
                val result = invoke(factory, *args.toTypedArray())
                assertEquals(1, result.exitCode, "$factory $platform")
                assertNull(result.stdout)
                assertNotNull(result.stderr)
            }
        }
    }

    @Test
    fun `inspection json rejects wrong field types through both platform clients`() {
        val cases = listOf(
            ::GetDeviceInfoCommand to """{"success":true,"displayWidth":"100","displayHeight":200,
                "displayRotation":0,"productName":"Phone","sdkVersion":35,"osVersion":"26.5"}""",
            ::GetInteractiveElementsCommand to """{"success":true,"count":1,"elements":[{"isEnabled":"true"}]}""",
            ::FindElementCommand to """{"found":false,"text":null}""",
        )
        for ((factory, payload) in cases) {
            for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
                respond(server, payload)
                val args = mutableListOf("-p", platform, "--json")
                if (factory == ::FindElementCommand) args += listOf("--text", "Login")
                val result = invoke(factory, *args.toTypedArray())
                assertEquals(1, result.exitCode, "$factory $platform")
                assertNull(result.stdout)
                assertNotNull(result.stderr)
            }
        }
    }

    @Test
    fun `inspection json validates RPC errors through every command and platform`() {
        for (factory in listOf(::GetDeviceInfoCommand, ::GetInteractiveElementsCommand, ::FindElementCommand)) {
            for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
                for (code in listOf("-32601", "\"-32601\"")) {
                    assertRpcErrorResult(factory, platform, server, code)
                }
            }
        }
    }

    private fun assertRpcErrorResult(
        factory: (Lazy<ComponentHolder>, CliCommandRunner) -> CliktCommand,
        platform: String,
        server: MockWebServer,
        code: String
    ) {
        server.enqueue(MockResponse().setBody("OK"))
        server.enqueue(MockResponse().setBody("OK"))
        val error = """{"code":$code,"message":"Unknown method","data":{"detail":null}}"""
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":17,"error":$error}"""))
        val args = mutableListOf("-p", platform, "--json")
        if (factory == ::FindElementCommand) args += listOf("--text", "Login")
        val result = invoke(factory, *args.toTypedArray())
        if (code == "-32601") assertEquals(CliResult(0, """{"error":$error}""", null), result)
        else {
            assertEquals(1, result.exitCode)
            assertNull(result.stdout)
            assertNotNull(result.stderr)
        }
    }

    @Test
    fun `find maps all selectors on both platforms and removes transport framing`() {
        for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
            respond(server, """{"found":true,"text":"quoted \\\"label\\\"","bounds":"[1,2][3,4]"}""")
            val args = mutableListOf("-p", platform, "--text", "exact", "--text-contains", "partial",
                "--resource-id", "login", "--class-name", "Button", "--content-description", "label", "--json")
            if (platform == "ios") args += listOf("--bundle-id", "app.id")
            val result = invoke(::FindElementCommand, *args.toTypedArray())
            assertEquals(0, result.exitCode, result.stderr)
            val output = JsonParser.parseString(result.stdout).asJsonObject
            assertTrue(output["found"].asBoolean)
            assertFalse(output.has("jsonrpc"))
            assertFalse(output.has("id"))
            val wire = request(server)
            assertEquals("ui.findElement", wire["method"].asString)
            val params = wire["params"].asJsonObject
            assertEquals("exact", params["text"].asString)
            assertEquals("partial", params["textContains"].asString)
            assertEquals("login", params["resourceId"].asString)
            assertEquals("Button", params["className"].asString)
            assertEquals("label", params["contentDescription"].asString)
            assertEquals(platform == "ios", params.has("bundleId"))
        }
    }

    @Test
    fun `ios app scope forwards unchanged to inspection and input operations`() {
        val cases = listOf(
            Triple(
                ::GetUiHierarchyCommand,
                arrayOf("-p", "ios", "--bundle-id", "com.example.app"),
                "ui.dumpHierarchy",
            ),
            Triple(
                ::GetInteractiveElementsCommand,
                arrayOf("-p", "ios", "--bundle-id", "com.example.app"),
                "ui.getInteractiveElements",
            ),
            Triple(
                ::InputTextCommand,
                arrayOf("-p", "ios", "hello", "--bundle-id", "com.example.app"),
                "ui.inputText",
            ),
        )
        for ((factory, args, method) in cases) {
            respond(iosServer, """{"success":true}""")
            val result = invoke(factory, *args)
            assertEquals(0, result.exitCode, result.stderr)
            val wire = request(iosServer)
            assertEquals(method, wire["method"].asString)
            assertEquals("com.example.app", wire["params"].asJsonObject["bundleId"].asString)
        }
    }

    @Test
    fun `omitting ios app scope preserves Springboard requests`() {
        val cases = listOf(
            Triple(::GetUiHierarchyCommand, arrayOf("-p", "ios"), "ui.dumpHierarchy"),
            Triple(::GetInteractiveElementsCommand, arrayOf("-p", "ios"), "ui.getInteractiveElements"),
            Triple(::InputTextCommand, arrayOf("-p", "ios", "hello"), "ui.inputText"),
        )
        for ((factory, args, method) in cases) {
            respond(iosServer, """{"success":true}""")
            assertEquals(0, invoke(factory, *args).exitCode)
            val wire = request(iosServer)
            assertEquals(method, wire["method"].asString)
            assertFalse(wire["params"].asJsonObject.has("bundleId"))
        }
    }

    @Test
    fun `invalid command arguments never access backend`() {
        val cases = listOf(
            ::FindElementCommand to arrayOf("-p", "android"),
            ::FindElementCommand to arrayOf("-p", "ios", "--bundle-id", "app.id"),
            ::FindElementCommand to arrayOf("-p", "android", "--text", "   "),
            ::FindElementCommand to arrayOf("-p", "android", "--text", "a", "--bundle-id", "app.id"),
            ::FindElementCommand to arrayOf("-p", "ios", "--text", "a", "--bundle-id", " "),
            ::SwipeOnElementCommand to arrayOf("-p", "ios", "up"),
            ::SwipeOnElementCommand to arrayOf("-p", "ios", "up", "--text", ""),
            ::SwipeOnElementCommand to arrayOf("-p", "android", "left", "--text", "a", "--bundle-id", "app.id"),
            ::TapOnElementCommand to arrayOf("-p", "android"),
            ::TapOnElementCommand to arrayOf("-p", "ios", "--bundle-id", "app.id"),
            ::TapOnElementCommand to arrayOf("-p", "android", "--text", " "),
            ::TapOnElementCommand to arrayOf("-p", "android", "--text", "a", "--bundle-id", "app.id"),
            ::TapOnElementCommand to arrayOf("-p", "ios", "--text", "a", "--bundle-id", " "),
            ::TapOnElementCommand to arrayOf("-p", "android", "--text", "a", "--timeout", "0"),
            ::TapOnElementCommand to arrayOf("-p", "ios", "--text", "a", "--timeout", "30001"),
            ::TapOnElementCommand to arrayOf("-p", "ios", "--text", "a", "--timeout", "not-a-number"),
            ::GetUiHierarchyCommand to arrayOf("-p", "android", "--bundle-id", "app.id"),
            ::GetInteractiveElementsCommand to arrayOf("-p", "android", "--bundle-id", "app.id"),
            ::InputTextCommand to arrayOf("-p", "android", "hello", "--bundle-id", "app.id"),
            ::GetUiHierarchyCommand to arrayOf("-p", "ios", "--bundle-id", " "),
            ::GetInteractiveElementsCommand to arrayOf("-p", "ios", "--bundle-id", " "),
            ::InputTextCommand to arrayOf("-p", "ios", "hello", "--bundle-id", " "),
            ::SwipeOnElementCommand to arrayOf("-p", "ios", "diagonal", "--text", "a"),
            ::SwipeOnElementCommand to arrayOf("-p", "ios", "up", "--text", "a", "--speed", "warp"),
            ::SwipeCommand to arrayOf("-p", "android", "1", "2", "3", "4", "--steps", "0"),
            ::SwipeCommand to arrayOf("-p", "ios", "1", "2", "3", "4", "--steps", "-1"),
            ::SwipeCommand to arrayOf("-p", "ios", "a", "2", "3", "4"),
            ::InfoAppCommand to arrayOf("-p", "ios")
        )
        for ((factory, args) in cases) assertEquals(2, invoke(factory, *args).exitCode, args.joinToString(" "))
        assertEquals(0, androidServer.requestCount)
        assertEquals(0, iosServer.requestCount)
        assertEquals(0, deviceCalls)
        assertFalse(components.isInitialized())
    }

    @Test
    fun `find and element swipe show app scope apart from selectors`() {
        for (factory in listOf(::FindElementCommand, ::SwipeOnElementCommand, ::TapOnElementCommand)) {
            val help = assertNotNull(factory(components) {}.getFormattedHelp())
            assertTrue(help.contains("Element selectors:"))
            assertTrue(help.contains("iOS app scope:"))
            val selectors = help.substringBefore("iOS app scope:")
            assertFalse(selectors.contains("--bundle-id"))
        }
    }

    @Test
    fun `all new commands require explicit valid platform`() {
        val factories = listOf(::FindElementCommand, ::SwipeCommand, ::SwipeOnElementCommand, ::TapOnElementCommand,
            ::ListAppsCommand, ::InfoAppCommand, ::AvailableDeviceCommand)
        for (factory in factories) {
            assertEquals(2, invoke(factory).exitCode)
            assertEquals(2, invoke(factory, "-p", "windows").exitCode)
        }
        assertFalse(components.isInitialized())
    }

    @Test
    fun `coordinate swipe forwards coordinates and positive step override`() {
        for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
            respond(server, """{"success":true}""")
            val result = invoke(
                ::SwipeCommand, "-p", platform, "10", "20", "30", "40", "--steps", "50"
            )
            assertEquals(0, result.exitCode)
            val wire = request(server)
            assertEquals("ui.swipe", wire["method"].asString)
            val expected = """{"startX":10,"startY":20,"endX":30,"endY":40,"steps":50}"""
            assertEquals(expected, wire["params"].toString())
        }
    }

    @Test
    fun `coordinate swipe defaults to twenty steps`() {
        respond(androidServer, """{"success":true}""")
        assertEquals(0, invoke(::SwipeCommand, "-p", "android", "10", "20", "30", "40").exitCode)
        assertEquals(20, request(androidServer)["params"].asJsonObject["steps"].asInt)
    }

    @Test
    fun `element swipe delegates native operation on both platforms`() {
        for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
            respond(server, """{"success":true}""")
            val result = invoke(
                ::SwipeOnElementCommand,
                "-p", platform, "left", "--resource-id", "carousel", "--speed", "fast"
            )
            assertEquals(0, result.exitCode, result.stderr)
            val wire = request(server)
            assertEquals("ui.swipeOnElement", wire["method"].asString)
            assertEquals("carousel", wire["params"].asJsonObject["resourceId"].asString)
            assertEquals("fast", wire["params"].asJsonObject["speed"].asString)
        }
    }

    @Test
    fun `element tap maps selectors app scope and timeout on both platforms`() {
        for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
            respond(server, """{"success":true,"message":"Tapped"}""")
            val args = mutableListOf(
                "-p", platform, "--text", "exact", "--text-contains", "partial",
                "--resource-id", "login", "--class-name", "Button", "--content-description", "label",
                "--timeout", "1234"
            )
            if (platform == "ios") args += listOf("--bundle-id", "com.example.app")

            val result = invoke(::TapOnElementCommand, *args.toTypedArray())
            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("""{"jsonrpc":"2.0","id":17,"result":{"success":true,"message":"Tapped"}}""", result.stdout)
            val wire = request(server)
            assertEquals("ui.tapOnElement", wire["method"].asString)
            val params = wire["params"].asJsonObject
            assertEquals("exact", params["text"].asString)
            assertEquals("partial", params["textContains"].asString)
            assertEquals("login", params["resourceId"].asString)
            assertEquals("Button", params["className"].asString)
            assertEquals("label", params["contentDescription"].asString)
            assertEquals(1234, params["timeoutMs"].asInt)
            assertEquals(platform == "ios", params.has("bundleId"))
        }
    }

    @Test
    fun `element tap defaults timeout and omits unscoped ios bundle`() {
        for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
            respond(server, """{"success":true}""")
            assertEquals(0, invoke(::TapOnElementCommand, "-p", platform, "--text", "Login").exitCode)
            val params = request(server)["params"].asJsonObject
            assertEquals(10_000, params["timeoutMs"].asInt)
            assertFalse(params.has("bundleId"))
        }
    }

    @Test
    fun `element tap native failures exit one and write stderr`() {
        for (payload in listOf("""{"success":false,"error":"Element not found"}""", """{"error":"Native failure"}""")) {
            respond(androidServer, payload)
            val result = invoke(::TapOnElementCommand, "-p", "android", "--text", "Login")
            assertEquals(1, result.exitCode)
            assertNull(result.stdout)
            assertNotNull(result.stderr)
        }
    }

    @Test
    fun `element tap exits three when the automation server is unreachable`() {
        val result = invoke(::TapOnElementCommand, "-p", "android", "--text", "Login")
        assertEquals(3, result.exitCode)
        assertNull(result.stdout)
        assertNotNull(result.stderr)
    }

    @Test
    fun `inspection json retains operation failures and malformed responses fail cleanly`() {
        for (payload in listOf("""{"found":false}""", """{"found":false,"error":"failed"}""")) {
            respond(androidServer, payload)
            val result = invoke(::FindElementCommand, "-p", "android", "--text", "missing", "--json")
            assertEquals(CliResult(0, payload, null), result)
        }
        val malformed = listOf(
            "not json", "[]", "{}", "{\"result\":null}", "{\"result\":1}",
            "{\"result\":{", "{result:{}}"
        )
        for (body in malformed) {
            androidServer.enqueue(MockResponse().setBody("OK"))
            androidServer.enqueue(MockResponse().setBody("OK"))
            androidServer.enqueue(MockResponse().setBody(body))
            val result = invoke(::FindElementCommand, "-p", "android", "--text", "a", "--json")
            assertEquals(1, result.exitCode, body)
            assertNull(result.stdout)
            assertNotNull(result.stderr)
        }
    }

    @Test
    fun `inspection json preserves rpc error without transport metadata`() {
        androidServer.enqueue(MockResponse().setBody("OK"))
        androidServer.enqueue(MockResponse().setBody("OK"))
        val response = """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Unknown method"}}"""
        androidServer.enqueue(MockResponse().setBody(response))
        val result = invoke(::GetDeviceInfoCommand, "-p", "android", "--json")
        assertEquals(0, result.exitCode)
        assertEquals("""{"error":{"code":-32601,"message":"Unknown method"}}""", result.stdout)
    }

    @Test
    fun `existing inspection commands unwrap json and preserve default text`() {
        for (factory in listOf(::GetDeviceInfoCommand, ::GetInteractiveElementsCommand)) {
            for ((platform, server) in listOf("android" to androidServer, "ios" to iosServer)) {
                val version = if (platform == "android") "\"sdkVersion\":35" else "\"osVersion\":\"26.5\""
                val payload = if (factory == ::GetDeviceInfoCommand) {
                    """{"success":true,"displayWidth":100,"displayHeight":200,""" +
                        """"displayRotation":0,"productName":"Phone",$version}"""
                } else """{"success":true,"count":0,"elements":[]}"""
                respond(server, payload)
                assertEquals(payload, invoke(factory, "-p", platform, "--json").stdout)
                respond(server, """{"success":true}""")
                val expected = """{"jsonrpc":"2.0","id":17,"result":{"success":true}}"""
                assertEquals(expected, invoke(factory, "-p", platform).stdout)
            }
        }
    }

    @Test
    fun `list apps json escapes identifiers and handles empty list on both platforms`() {
        for (platform in listOf("android", "ios")) {
            apps = listOf("app.\"quoted", "app.two")
            val result = invoke(::ListAppsCommand, "-p", platform, "--json")
            assertEquals(0, result.exitCode)
            val actualApps = JsonParser.parseString(result.stdout).asJsonObject["apps"].asJsonArray
                .map { it.asString }
            assertEquals(apps, actualApps)
            val textResult = invoke(::ListAppsCommand, "-p", platform)
            assertEquals("Found these apps: ${apps.joinToString(", ")}", textResult.stdout)
            apps = emptyList()
            assertEquals("{\"apps\":[]}", invoke(::ListAppsCommand, "-p", platform, "--json").stdout)
        }
    }

    @Test
    fun `app info exposes id and unformatted backend details`() {
        for (platform in listOf("android", "ios")) {
            val result = invoke(::InfoAppCommand, "-p", platform, "app.id", "--json")
            assertEquals(0, result.exitCode)
            val json = JsonParser.parseString(result.stdout).asJsonObject
            assertEquals("app.id", json["id"].asString)
            assertEquals("details for app.id\nversionName=1.0", json["rawInfo"].asString)
            val textResult = invoke(::InfoAppCommand, "-p", platform, "app.id")
            assertTrue(textResult.stdout!!.contains("App Information for app.id"))
        }
    }

    @Test
    fun `available device includes identity and null optional metadata`() {
        for (platform in listOf("android", "ios")) {
            val result = invoke(::AvailableDeviceCommand, "-p", platform, "--json")
            assertEquals(0, result.exitCode)
            val json = JsonParser.parseString(result.stdout).asJsonObject
            if (platform == "android") {
                assertEquals("device-1", json["id"].asString)
                assertEquals("Phone", json["name"].asString)
                assertEquals("ANDROID", json["type"].asString)
                assertEquals("device", json["state"].asString)
                assertEquals("Pixel", json["modelName"].asString)
                assertEquals("15", json["osVersion"].asString)
                assertEquals("35", json["sdkVersion"].asString)
            } else {
                assertEquals("simulator-1", json["id"].asString)
                assertEquals("iPhone 17", json["name"].asString)
                assertEquals("IOS_SIMULATOR", json["type"].asString)
                assertEquals("Booted", json["state"].asString)
                assertEquals("26.5", json["osVersion"].asString)
                assertEquals("iPhone 17", json["modelName"].asString)
            }
        }
    }

    @Test
    fun `missing device returns exit four for metadata commands`() {
        deviceFailure = true
        for (factory in listOf(::ListAppsCommand, ::AvailableDeviceCommand)) {
            for (platform in listOf("android", "ios")) {
                val result = invoke(factory, "-p", platform, "--json")
                assertEquals(4, result.exitCode)
                assertNull(result.stdout)
            }
        }
        assertEquals(4, invoke(::InfoAppCommand, "-p", "ios", "app.id", "--json").exitCode)
    }

    @Test
    fun `unreachable server returns exit three`() {
        iosServer.shutdown()
        val result = invoke(::FindElementCommand, "-p", "ios", "--text", "a", "--json")
        assertEquals(3, result.exitCode)
        assertNull(result.stdout)
    }
}
