package com.example.visiontest.cli

import com.example.visiontest.NoDeviceAvailableException
import com.example.visiontest.android.Android
import com.example.visiontest.android.AutomationClient
import com.example.visiontest.cli.commands.AvailableDeviceCommand
import com.example.visiontest.cli.commands.FindElementCommand
import com.example.visiontest.cli.commands.GetDeviceInfoCommand
import com.example.visiontest.cli.commands.GetInteractiveElementsCommand
import com.example.visiontest.cli.commands.GetUiHierarchyCommand
import com.example.visiontest.cli.commands.InfoAppCommand
import com.example.visiontest.cli.commands.InputTextCommand
import com.example.visiontest.cli.commands.ListAppsCommand
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

    private fun request(server: MockWebServer): com.google.gson.JsonObject {
        assertEquals("/health", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        assertEquals("/health", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        return JsonParser.parseString(request.body.readUtf8()).asJsonObject
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
            Triple(::GetUiHierarchyCommand, arrayOf("-p", "ios", "--bundle-id", "com.example.app"), "ui.dumpHierarchy"),
            Triple(::GetInteractiveElementsCommand, arrayOf("-p", "ios", "--bundle-id", "com.example.app"), "ui.getInteractiveElements"),
            Triple(::InputTextCommand, arrayOf("-p", "ios", "hello", "--bundle-id", "com.example.app"), "ui.inputText"),
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
        for (payload in listOf("""{"found":false}""", """{"success":false,"error":"failed"}""")) {
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
                respond(server, """{"success":true}""")
                assertEquals("""{"success":true}""", invoke(factory, "-p", platform, "--json").stdout)
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
