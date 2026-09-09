# iOS App Scope and Element Tap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development (recommended) or agentico:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix issue #50 by exposing consistent iOS application scope in the CLI and add reliable CLI/MCP element taps that wait natively for actionability.

**Architecture:** Thin CLI and MCP adapters share platform registrar operations. App scope is a Clikt option group separate from selectors. A new `ui.tapOnElement` JSON-RPC method performs the bounded readiness wait and tap in each native server, following [TD-010](../../decisions/TD-010-keep-wait-and-tap-atomic-in-native-servers.md), while Kotlin clients provide transport and method-specific timeout grace.

**Tech Stack:** Kotlin/JVM 17, Clikt 4.4, MCP Kotlin SDK, Gson, MockWebServer, Android UIAutomator, Kotlin/JUnit, Swift/XCUITest, XCTest.

---

## File Map

- `app/src/main/kotlin/com/example/visiontest/cli/IosAppScopeOptions.kt`: owns CLI application-scope parsing, help, and validation.
- `app/src/main/kotlin/com/example/visiontest/cli/ElementSelectorOptions.kt`: owns only element selectors.
- `app/src/main/kotlin/com/example/visiontest/cli/commands/{GetUiHierarchy,GetInteractiveElements,InputText,FindElement,SwipeOnElement,TapOnElement}Command.kt`: thin CLI adapters.
- `app/src/main/kotlin/com/example/visiontest/cli/VisionTestCli.kt`: registers `tap_on_element`.
- `app/src/main/kotlin/com/example/visiontest/{android/AutomationClient,ios/IOSAutomationClient}.kt`: serialize `ui.tapOnElement` requests.
- `app/src/main/kotlin/com/example/visiontest/common/JsonRpcHttpClient.kt`: supports a per-request read timeout and validates operation responses.
- `app/src/main/kotlin/com/example/visiontest/config/{AutomationConfig,IOSAutomationConfig}.kt`: shared tap timing limits.
- `app/src/main/kotlin/com/example/visiontest/tools/{AndroidAutomationToolRegistrar,IOSAutomationToolRegistrar}.kt`: shared CLI/MCP tap behavior and schemas.
- `automation-server/src/main/java/com/example/automationserver/uiautomator/ElementTapWait.kt`: testable Android wait state machine.
- `automation-server/src/main/java/com/example/automationserver/uiautomator/BaseUiAutomatorBridge.kt`: direct `UiObject2.click()` integration.
- `automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt`: Android JSON-RPC dispatch.
- `ios-automation-server/IOSAutomationServerUITests/Helpers/Helpers.swift`: request validation and testable wait state machine.
- `ios-automation-server/IOSAutomationServerUITests/Bridge/XCUITestBridge.swift`: `XCUIElement.tap()` integration.
- `ios-automation-server/IOSAutomationServerUITests/Server/JsonRpcServer.swift`: iOS JSON-RPC dispatch and main-thread timeout budget.
- Existing platform, registrar, CLI, MCP, and documentation tests listed in the tasks below document the public behavior.

### Task 1: Separate iOS app scope and fix issue #50

**Files:**
- Create: `app/src/main/kotlin/com/example/visiontest/cli/IosAppScopeOptions.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/ElementSelectorOptions.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/commands/GetUiHierarchyCommand.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/commands/GetInteractiveElementsCommand.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/commands/InputTextCommand.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/commands/FindElementCommand.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/commands/SwipeOnElementCommand.kt`
- Test: `app/src/test/kotlin/com/example/visiontest/cli/ParityCliTest.kt`

- [ ] **Step 1: Write failing CLI tests for forwarding, omission, validation, and help grouping**

Add imports for the three command classes and `PrintHelpMessage`, then add focused cases shaped as follows:

```kotlin
@Test
fun `ios app-scoped commands forward bundle id unchanged`() {
    val cases = listOf(
        ::GetUiHierarchyCommand to emptyArray(),
        ::GetInteractiveElementsCommand to emptyArray(),
        ::InputTextCommand to arrayOf("hello"),
    )
    for ((factory, tail) in cases) {
        respond(iosServer, """{"success":true}""")
        val args = arrayOf("-p", "ios", "--bundle-id", "com.example.app", *tail)
        assertEquals(0, invoke(factory, *args).exitCode)
        assertEquals("com.example.app", request(iosServer)["params"].asJsonObject["bundleId"].asString)
    }
}

@Test
fun `ios app-scoped commands omit bundle id for Springboard`() {
    respond(iosServer, """{"success":true}""")
    assertEquals(0, invoke(::GetUiHierarchyCommand, "-p", "ios").exitCode)
    assertFalse(request(iosServer)["params"].asJsonObject.has("bundleId"))
}

@Test
fun `bundle id validation happens before backend access`() {
    val cases = listOf(
        ::GetUiHierarchyCommand to arrayOf("-p", "android", "--bundle-id", "com.example"),
        ::GetInteractiveElementsCommand to arrayOf("-p", "ios", "--bundle-id", "   "),
        ::InputTextCommand to arrayOf("-p", "android", "--bundle-id", "com.example", "hello"),
    )
    for ((factory, args) in cases) assertEquals(2, invoke(factory, *args).exitCode)
    assertFalse(components.isInitialized())
    assertEquals(0, androidServer.requestCount)
    assertEquals(0, iosServer.requestCount)
}
```

Add a help assertion that captures `PrintHelpMessage` from `FindElementCommand(...).parse(listOf("--help"))`, then asserts `--bundle-id` appears after `iOS app scope` and not in the text slice headed by `Element selectors`. Retain the existing bundle-only rejection cases for `find_element` and `swipe_on_element`.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./gradlew :app:test --tests 'com.example.visiontest.cli.ParityCliTest'
```

Expected: compilation or assertion failures because the three commands do not accept/forward `--bundle-id` and no dedicated help group exists.

- [ ] **Step 3: Implement the shared app-scope option group**

Create:

```kotlin
package com.example.visiontest.cli

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.option

internal class IosAppScopeOptions : OptionGroup("iOS app scope") {
    val bundleId by option(
        "--bundle-id",
        help = "Target app bundle ID on iOS; omit only for Springboard",
    )

    fun validate(platform: Platform) {
        require(platform == Platform.Ios || bundleId == null) { "--bundle-id is only supported on iOS" }
        require(bundleId?.isNotBlank() != false) { "--bundle-id must not be blank" }
    }
}
```

Remove `bundleId` and platform-specific checks from `ElementSelectorOptions`; leave `fun validate()` responsible only for nonblank selectors and the at-least-one-selector rule. In each affected command, delegate both groups with `provideDelegate`, call `appScope.validate(platform)` before `components.value`, and pass `appScope.bundleId` only to iOS registrar calls. Add the injectable `CliCommandRunner = ::runCliCommand` constructor parameter to `GetUiHierarchyCommand` and `InputTextCommand`, matching `GetInteractiveElementsCommand`, so the integration tests do not trap `exitProcess`.

For example, the iOS hierarchy branch becomes:

```kotlin
private val appScope by IosAppScopeOptions()

override fun run() = runner {
    appScope.validate(platform)
    requireServerRunning { components.value.isServerRunning(platform) }
    when (platform) {
        Platform.Android -> components.value.androidAutomationRegistrar.getUiHierarchy()
        Platform.Ios -> components.value.iosAutomationRegistrar.getUiHierarchy(appScope.bundleId)
    }
}
```

Apply the same ordering to interactive elements and input. In find/swipe, use `selectors.validate()` followed by `appScope.validate(platform)` and replace `selectors.bundleId` with `appScope.bundleId`.

- [ ] **Step 4: Run focused and registrar regression tests and verify GREEN**

Run:

```bash
./gradlew :app:test --tests 'com.example.visiontest.cli.ParityCliTest' --tests 'com.example.visiontest.tools.IOSSwipeToolRegistrarTest'
```

Expected: all selected tests pass; invalid invocations produce no HTTP requests.

- [ ] **Step 5: Commit the issue #50 fix**

```bash
git add app/src/main/kotlin/com/example/visiontest/cli app/src/test/kotlin/com/example/visiontest/cli/ParityCliTest.kt
git commit -m "fix: expose iOS app scope in CLI commands"
```

### Task 2: Implement and test Android native wait-and-tap

**Files:**
- Create: `automation-server/src/main/java/com/example/automationserver/uiautomator/ElementTapWait.kt`
- Create: `automation-server/src/test/java/com/example/automationserver/uiautomator/ElementTapWaitTest.kt`
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/BaseUiAutomatorBridge.kt`
- Modify: `automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt`
- Test: `automation-server/src/androidTest/java/com/example/automationserver/UiAutomatorBridgeInstrumented.kt`

- [ ] **Step 1: Write failing unit tests for the Android state machine**

Create tests that use a mutable fake clock and assert all three transitions:

```kotlin
@Test
fun `taps the same candidate once it becomes actionable`() {
    var now = 0L
    var attempts = 0
    val tapped = mutableListOf<String>()
    val result = waitAndTapElement(
        timeoutMs = 1_000,
        pollIntervalMs = 500,
        selectorDescription = "text='Continue'",
        nowMs = { now },
        sleepMs = { now += it },
        lookup = {
            attempts++
            when (attempts) {
                1 -> ElementTapCandidate<String>()
                2 -> ElementTapCandidate("button", actionable = false)
                else -> ElementTapCandidate("button", actionable = true)
            }
        },
        tap = { tapped += it },
    )
    assertTrue(result.success)
    assertEquals(listOf("button"), tapped)
}
```

Add separate tests asserting `Element not found` when every candidate is absent, `Element found but not tappable` after a blocked candidate, and zero taps in both timeout cases.

- [ ] **Step 2: Run the Android unit test and verify RED**

Run:

```bash
./gradlew :automation-server:testDebugUnitTest --tests 'com.example.automationserver.uiautomator.ElementTapWaitTest'
```

Expected: compilation fails because `ElementTapCandidate` and `waitAndTapElement` do not exist.

- [ ] **Step 3: Implement the pure Android state machine**

Create an internal generic candidate and helper. The helper must check immediately, sleep no longer than the remaining budget, remember whether a blocked match was seen, call `tap` once, and return an `OperationResult`:

```kotlin
internal data class ElementTapCandidate<T>(
    val element: T? = null,
    val actionable: Boolean = false,
)

internal fun <T> waitAndTapElement(
    timeoutMs: Long,
    pollIntervalMs: Long,
    selectorDescription: String,
    nowMs: () -> Long,
    sleepMs: (Long) -> Unit,
    lookup: () -> ElementTapCandidate<T>,
    tap: (T) -> Unit,
): OperationResult {
    val startedAt = nowMs()
    var sawBlockedElement = false
    while (true) {
        val candidate = lookup()
        val element = candidate.element
        if (element != null) {
            sawBlockedElement = sawBlockedElement || !candidate.actionable
            if (candidate.actionable) {
                tap(element)
                return OperationResult(success = true)
            }
        }
        val remaining = timeoutMs - (nowMs() - startedAt)
        if (remaining <= 0) {
            val state = if (sawBlockedElement) "Element found but not tappable" else "Element not found"
            return OperationResult(false, "$state after ${timeoutMs}ms ($selectorDescription)")
        }
        sleepMs(minOf(pollIntervalMs, remaining))
    }
}
```

- [ ] **Step 4: Add the Android bridge and JSON-RPC dispatch**

Add `tapOnElement(...)` beside `swipeOnElement`. Build the selector once, compute the display `Rect`, and call the helper with `SystemClock.elapsedRealtime`, `SystemClock.sleep`, `device.findObject(selector)`, readiness `element.isEnabled && !bounds.isEmpty && Rect.intersects(bounds, display)`, and `UiObject2::click`. Wrap unexpected lookup/tap exceptions in `OperationResult(false, e.message)` as other bridge operations do.

In `JsonRpcServerInstrumented`, add `ui.tapOnElement`. Parse the five selectors and `timeoutMs`; reject missing selectors and timeouts outside `1..30_000` with `InvalidParamsException`; then delegate all values to the bridge. Add an instrumented parsing/delegation test consistent with the existing `ui.swipeOnElement` coverage.

- [ ] **Step 5: Run Android tests and verify GREEN**

Run:

```bash
./gradlew :automation-server:testDebugUnitTest
./gradlew :automation-server:compileDebugAndroidTestKotlin
```

Expected: both commands succeed; the state-machine tests report zero failures.

- [ ] **Step 6: Commit Android native support**

```bash
git add automation-server/src/main automation-server/src/test automation-server/src/androidTest
git commit -m "feat: wait and tap Android elements natively"
```

### Task 3: Implement and test iOS native wait-and-tap

**Files:**
- Modify: `ios-automation-server/IOSAutomationServerUITests/Helpers/Helpers.swift`
- Modify: `ios-automation-server/IOSAutomationServerTests/HelpersTests.swift`
- Modify: `ios-automation-server/IOSAutomationServerUITests/Bridge/XCUITestBridge.swift`
- Modify: `ios-automation-server/IOSAutomationServerUITests/Server/JsonRpcServer.swift`

- [ ] **Step 1: Write failing Swift tests for request validation and state transitions**

Add tests for selectors, bundle scope, default/invalid timeout, immediate readiness, delayed readiness, absent timeout, blocked timeout, and zero taps on failure. Use a generic readiness enum so tests need no `XCUIElement`:

```swift
func testElementTapWaitsForActionabilityAndTapsOnce() {
    var now: TimeInterval = 0
    var attempts = 0
    var tapped: [String] = []
    let result = performElementTap(
        timeoutMs: 1_000,
        pollIntervalMs: 500,
        selectorDescription: "text='Continue'",
        now: { now },
        sleep: { now += $0 },
        lookup: {
            attempts += 1
            if attempts == 1 { return .absent }
            if attempts == 2 { return .blocked("button") }
            return .ready("button")
        },
        tap: { tapped.append($0) }
    )
    XCTAssertTrue(result.success)
    XCTAssertEqual(tapped, ["button"])
}
```

- [ ] **Step 2: Run iOS unit tests and verify RED**

Run:

```bash
xcodebuild test -project ios-automation-server/IOSAutomationServer.xcodeproj -scheme IOSAutomationServer -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:IOSAutomationServerTests
```

Expected: compilation fails because `ElementTapRequest`, `ElementTapReadiness`, and `performElementTap` do not exist.

- [ ] **Step 3: Implement the Swift request model and pure state machine**

Add `ElementTapRequest` using `validatedString` for selector/app-scope fields and `intParam` for `timeoutMs`. Require a selector and `1...30_000`; default to 10,000. Implement:

```swift
enum ElementTapReadiness<Element> {
    case absent
    case blocked(Element)
    case ready(Element)
}

func performElementTap<Element>(
    timeoutMs: Int,
    pollIntervalMs: Int,
    selectorDescription: String,
    now: () -> TimeInterval,
    sleep: (TimeInterval) -> Void,
    lookup: () -> ElementTapReadiness<Element>,
    tap: (Element) -> Void
) -> OperationResult {
    let startedAt = now()
    var sawBlockedElement = false
    while true {
        switch lookup() {
        case .ready(let element):
            tap(element)
            return OperationResult(success: true, error: nil)
        case .blocked:
            sawBlockedElement = true
        case .absent:
            break
        }
        let elapsedMs = Int((now() - startedAt) * 1_000)
        let remainingMs = timeoutMs - elapsedMs
        if remainingMs <= 0 {
            let state = sawBlockedElement ? "Element found but not tappable" : "Element not found"
            return OperationResult(success: false, error: "\(state) after \(timeoutMs)ms (\(selectorDescription))")
        }
        sleep(TimeInterval(min(pollIntervalMs, remainingMs)) / 1_000)
    }
}
```

- [ ] **Step 4: Connect XCUITest and JSON-RPC**

Add `XCUITestBridge.tapOnElement(_:)`, using existing `lookupElement`, returning `.ready(element)` only for `exists && isEnabled && isHittable`, `.blocked(element)` for an existing non-actionable match, and `.absent` otherwise. Tap with `element.tap()`.

Add the dispatch branch:

```swift
case "ui.tapOnElement":
    return bridge.tapOnElement(try ElementTapRequest(params: params)).toDictionary()
```

Increase `runOnMainThread`'s limit and diagnostic from 30 to 35 seconds so a 30-second element deadline completes before the server guard. Keep the limit as one named constant used by both the semaphore deadline and message.

- [ ] **Step 5: Run iOS unit tests and verify GREEN**

Run the same `xcodebuild test` command from Step 2.

Expected: `IOSAutomationServerTests` passes with no failures.

- [ ] **Step 6: Commit iOS native support**

```bash
git add ios-automation-server/IOSAutomationServerUITests ios-automation-server/IOSAutomationServerTests
git commit -m "feat: wait and tap iOS elements natively"
```

### Task 4: Add Kotlin transport and client methods

**Files:**
- Modify: `app/src/main/kotlin/com/example/visiontest/common/JsonRpcHttpClient.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/config/AutomationConfig.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/config/IOSAutomationConfig.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/android/AutomationClient.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/ios/IOSAutomationClient.kt`
- Test: `app/src/test/kotlin/com/example/visiontest/android/AutomationClientTest.kt`
- Test: `app/src/test/kotlin/com/example/visiontest/ios/IOSAutomationClientTest.kt`
- Test: `app/src/test/kotlin/com/example/visiontest/consumer/IOSAutomationClientPublicApiTest.kt`

- [ ] **Step 1: Write failing client serialization tests**

For each client, invoke `tapOnElement` with every selector and timeout, inspect the MockWebServer body, and require method `ui.tapOnElement`, unchanged selector keys, iOS `bundleId`, and numeric `timeoutMs`. Add an omission test proving absent optional selectors/app scope are not serialized. The iOS consumer-package test must call the member without an extension import.

```kotlin
client.tapOnElement(
    IOSElementSelectors(text = "Continue", bundleId = "com.example.app"),
    timeoutMs = 15_000,
)
assertEquals("ui.tapOnElement", body["method"].asString)
assertEquals(15_000, body["params"].asJsonObject["timeoutMs"].asInt)
```

- [ ] **Step 2: Run client tests and verify RED**

```bash
./gradlew :app:test --tests 'com.example.visiontest.android.AutomationClientTest' --tests 'com.example.visiontest.ios.IOSAutomationClientTest' --tests 'com.example.visiontest.consumer.IOSAutomationClientPublicApiTest'
```

Expected: compilation fails because `tapOnElement` is absent.

- [ ] **Step 3: Implement timing constants and per-request timeout grace**

Add `ELEMENT_TAP_POLL_INTERVAL_MS = 500L`, `ELEMENT_TAP_DEFAULT_TIMEOUT_MS = 10_000L`, `ELEMENT_TAP_MAX_TIMEOUT_MS = 30_000L`, and `ELEMENT_TAP_TRANSPORT_GRACE_MS = 10_000` to both platform configs.

Extend `sendRequest` additively:

```kotlin
suspend fun sendRequest(
    method: String,
    params: Map<String, Any>? = null,
    id: Int = 1,
    readTimeoutMs: Int = REQUEST_TIMEOUT_MS,
): String
```

Assign `connection.readTimeout = readTimeoutMs`; preserve the 30-second default for every existing call.

- [ ] **Step 4: Implement platform client methods**

Use selector data classes, append `timeoutMs`, and call `sendRequest` with `timeoutMs + ELEMENT_TAP_TRANSPORT_GRACE_MS`. Android maps `resourceId/className/contentDescription`; iOS maps `identifier/elementType/label` to those wire names and includes `bundleId`.

```kotlin
suspend fun tapOnElement(selectors: IOSElementSelectors, timeoutMs: Int): String {
    val params = mutableMapOf<String, Any>("timeoutMs" to timeoutMs)
    selectors.text?.let { params["text"] = it }
    selectors.textContains?.let { params["textContains"] = it }
    selectors.identifier?.let { params["resourceId"] = it }
    selectors.elementType?.let { params["className"] = it }
    selectors.label?.let { params["contentDescription"] = it }
    selectors.bundleId?.let { params["bundleId"] = it }
    return sendRequest(
        "ui.tapOnElement",
        params,
        readTimeoutMs = timeoutMs + IOSAutomationConfig.ELEMENT_TAP_TRANSPORT_GRACE_MS,
    )
}
```

- [ ] **Step 5: Run client tests and verify GREEN**

Run the Step 2 command. Expected: all selected tests pass.

- [ ] **Step 6: Commit transport and clients**

```bash
git add app/src/main/kotlin/com/example/visiontest/common app/src/main/kotlin/com/example/visiontest/config app/src/main/kotlin/com/example/visiontest/android app/src/main/kotlin/com/example/visiontest/ios app/src/test/kotlin/com/example/visiontest/android app/src/test/kotlin/com/example/visiontest/ios app/src/test/kotlin/com/example/visiontest/consumer
git commit -m "feat: add element tap client operations"
```

### Task 5: Add shared registrar operations and MCP tools

**Files:**
- Modify: `app/src/main/kotlin/com/example/visiontest/common/JsonRpcHttpClient.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrar.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt`
- Test: `app/src/test/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrarTest.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/tools/IOSElementTapToolRegistrarTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt`

- [ ] **Step 1: Write failing registrar tests**

For each platform, capture the registered MCP handler and schema. Assert the five selector properties, optional `timeoutMs`, iOS `bundleId`, no required schema fields, and tool timeout 45,000 ms. Invoke the handler and verify health check followed by `ui.tapOnElement`. Add direct-operation tests for selector and timeout validation before HTTP access, default timeout 10,000, server-not-running behavior, native `success:false` conversion to `CommandExecutionException`, and raw success return.

```kotlin
assertFailsWith<IllegalArgumentException> {
    registrar.tapOnElement(IOSElementSelectors(bundleId = "com.example"), 10_000)
}
assertFailsWith<IllegalArgumentException> {
    registrar.tapOnElement(IOSElementSelectors(text = "Continue"), 30_001)
}
assertEquals(0, http.requestCount)
```

Add `tap_on_element` and `ios_tap_on_element` to `EXPECTED_TOOLS` before production registration so the E2E contract is RED.

- [ ] **Step 2: Run registrar and MCP E2E tests and verify RED**

```bash
./gradlew :app:test --tests 'com.example.visiontest.tools.AndroidAutomationToolRegistrarTest' --tests 'com.example.visiontest.tools.IOSElementTapToolRegistrarTest'
./gradlew :app:e2eTest --tests 'com.example.visiontest.McpStdioE2ETest.tools list returns exactly the expected tool contract'
```

Expected: missing operations/tools fail compilation or contract assertions.

- [ ] **Step 3: Add common operation-response validation**

Add an internal top-level `requireSuccessfulOperation(response: String, operation: String)` beside `JsonRpcHttpClient`. It parses the top-level object, throws `CommandExecutionException` for JSON-RPC `error`, missing/non-object result, missing/non-boolean `success`, or `success:false`, and returns the unchanged raw response for `success:true`. Use `result.error` when available. Keep existing client methods unchanged; the new registrar operations call this helper around the raw `tapOnElement` response so result/error translation remains in the behavior-bearing registrar.

- [ ] **Step 4: Implement registrar operations and MCP registrations**

Each internal operation validates before `requireServer`, resolves the default, then calls its platform client:

```kotlin
internal suspend fun tapOnElement(
    selectors: IOSElementSelectors,
    timeoutMs: Int? = null,
): String {
    require(selectors.hasAnySelector()) {
        "At least one selector required (text, textContains, resourceId, className, or contentDescription)"
    }
    val timeout = timeoutMs ?: IOSAutomationConfig.ELEMENT_TAP_DEFAULT_TIMEOUT_MS.toInt()
    require(timeout in 1..IOSAutomationConfig.ELEMENT_TAP_MAX_TIMEOUT_MS.toInt()) {
        "timeoutMs must be between 1 and ${IOSAutomationConfig.ELEMENT_TAP_MAX_TIMEOUT_MS}, got $timeout"
    }
    requireServer()
    return requireSuccessfulOperation(
        iosAutomationClient.tapOnElement(selectors, timeout),
        "tap on element",
    )
}
```

Register the Android and iOS MCP tools from `registerTools`. Their descriptions must say they wait until actionable, do not auto-scroll, default to 10 seconds, and require a selector. Build explicit JSON schemas with string selector properties and integer `timeoutMs` constrained to minimum 1, maximum 30,000, default 10,000. Map requests into `AndroidElementSelectors`/`IOSElementSelectors` and delegate to the internal operation.

- [ ] **Step 5: Run registrar, client, and MCP contract tests and verify GREEN**

Run the Step 2 commands plus the client tests from Task 4. Expected: all pass and `tools/list` contains exactly the two new tools.

- [ ] **Step 6: Commit registrar and MCP support**

```bash
git add app/src/main/kotlin/com/example/visiontest/common/JsonRpcHttpClient.kt app/src/main/kotlin/com/example/visiontest/tools app/src/test/kotlin/com/example/visiontest/tools app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt
git commit -m "feat: expose waiting element taps over MCP"
```

### Task 6: Add the unified CLI command

**Files:**
- Create: `app/src/main/kotlin/com/example/visiontest/cli/commands/TapOnElementCommand.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/VisionTestCli.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/ParityCliTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/VisionTestCliTest.kt`

- [ ] **Step 1: Write failing CLI tests**

Add `TapOnElementCommand` to the platform factory list and invalid-input matrix. Test both platforms forwarding selectors and timeout, iOS bundle forwarding, default 10,000, bundle omission, backend-free rejection, native timeout mapping to exit code 1/stderr, and registration in the root command.

```kotlin
@Test
fun `tap on element delegates selectors scope and timeout`() {
    respond(iosServer, """{"success":true}""")
    val result = invoke(
        ::TapOnElementCommand,
        "-p", "ios", "--resource-id", "continue", "--bundle-id", "com.example", "--timeout", "15000",
    )
    assertEquals(0, result.exitCode, result.stderr)
    val wire = request(iosServer)
    assertEquals("ui.tapOnElement", wire["method"].asString)
    assertEquals("continue", wire["params"].asJsonObject["resourceId"].asString)
    assertEquals("com.example", wire["params"].asJsonObject["bundleId"].asString)
    assertEquals(15_000, wire["params"].asJsonObject["timeoutMs"].asInt)
}
```

- [ ] **Step 2: Run CLI tests and verify RED**

```bash
./gradlew :app:test --tests 'com.example.visiontest.cli.ParityCliTest' --tests 'com.example.visiontest.cli.VisionTestCliTest'
```

Expected: compilation or registration assertion fails because the command is absent.

- [ ] **Step 3: Implement the thin CLI adapter**

Create:

```kotlin
class TapOnElementCommand(
    private val components: Lazy<ComponentHolder>,
    private val runner: CliCommandRunner = ::runCliCommand,
) : CliktCommand(name = "tap_on_element", help = "Wait for and tap a UI element") {
    private val platform by platformOption()
    private val selectors by ElementSelectorOptions()
    private val appScope by IosAppScopeOptions()
    private val timeout by option(
        "--timeout",
        help = "Max wait in milliseconds (default 10000, max 30000)",
    ).int()

    override fun run() = runner {
        selectors.validate()
        appScope.validate(platform)
        require(timeout == null || timeout in 1..30_000) { "--timeout must be between 1 and 30000" }
        requireServerRunning { components.value.isServerRunning(platform) }
        when (platform) {
            Platform.Android -> components.value.androidAutomationRegistrar.tapOnElement(
                AndroidElementSelectors(
                    selectors.text, selectors.textContains, selectors.resourceId,
                    selectors.className, selectors.contentDescription,
                ),
                timeout,
            )
            Platform.Ios -> components.value.iosAutomationRegistrar.tapOnElement(
                IOSElementSelectors(
                    selectors.text, selectors.textContains, selectors.resourceId,
                    selectors.className, selectors.contentDescription, appScope.bundleId,
                ),
                timeout,
            )
        }
    }
}
```

Use named constructor arguments if the selector data-class order differs. Register the command in `VisionTestCli` next to `TapByCoordinatesCommand`.

- [ ] **Step 4: Run CLI tests and verify GREEN**

Run the Step 2 command. Expected: both classes pass with `tap_on_element` in the exact command set.

- [ ] **Step 5: Commit CLI support**

```bash
git add app/src/main/kotlin/com/example/visiontest/cli app/src/test/kotlin/com/example/visiontest/cli
git commit -m "feat: add waiting tap on element CLI command"
```

### Task 7: Update behavioral contracts and user guidance

**Files:**
- Modify: `docs/agentico/specs/cli.md`
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `app/src/main/resources/agent-instructions.md`
- Create: `docs/agentico/specs/element-tap.md`
- Modify: `docs/installation.md`
- Test: `app/src/test/kotlin/com/example/visiontest/cli/InitCommandTest.kt`
- Test: `app/src/test/kotlin/com/example/visiontest/cli/InitCommandE2ETest.kt`

- [ ] **Step 1: Add contract assertions before documentation changes**

Where resource-copy tests assert distributed guidance, add assertions for `tap_on_element`, implicit actionability wait, `--timeout`, and the statement that `--bundle-id` is application scope rather than a selector.

- [ ] **Step 2: Run resource tests and verify RED**

```bash
./gradlew :app:test --tests 'com.example.visiontest.cli.InitCommandTest'
./gradlew :app:e2eTest --tests 'com.example.visiontest.cli.InitCommandE2ETest'
```

Expected: new content assertions fail.

- [ ] **Step 3: Update all public documentation**

Change the CLI contract from 22 to 23 commands. Add `--bundle-id` to hierarchy, interactive elements, and input on iOS. Add `tap_on_element` with selectors, iOS app scope, and timeout. State that it waits for an actionable element, does not auto-scroll, defaults to 10 seconds, and fails without tapping at 30 seconds maximum.

In `README.md`, include one iOS scoped inspection example and one direct tap example. In `CLAUDE.md`, update both tool and CLI tables. In the distributable agent instructions, keep all guidance self-contained and direct agents to prefer `tap_on_element` over coordinate taps when a stable selector exists.

In `docs/agentico/specs/element-tap.md`, record the observable CLI/MCP selectors, timeout limits, native readiness rules, no-auto-scroll rule, error semantics, app scope, and compatibility scenarios. In `docs/installation.md`, state that `ios_tap_on_element` requires a bundle containing `ui.tapOnElement`; older bundles return `Method not found`. Include the parallel Android APK update requirement.

- [ ] **Step 4: Run resource and contract tests and verify GREEN**

Run the Step 2 commands and `./gradlew :app:test --tests 'com.example.visiontest.cli.VisionTestCliTest'`.

- [ ] **Step 5: Commit documentation**

```bash
git add docs README.md CLAUDE.md app/src/main/resources/agent-instructions.md app/src/test/kotlin/com/example/visiontest/cli/InitCommandTest.kt app/src/test/kotlin/com/example/visiontest/cli/InitCommandE2ETest.kt
git commit -m "docs: document app-scoped waiting element taps"
```

### Task 8: Full verification and decision acceptance

**Files:**
- Modify: `docs/decisions/TD-010-keep-wait-and-tap-atomic-in-native-servers.md`
- Modify: `docs/decisions/README.md`

- [ ] **Step 1: Run the full repository gate**

```bash
./gradlew :app:test
./gradlew :app:e2eTest
./gradlew build
xcodebuild test -project ios-automation-server/IOSAutomationServer.xcodeproj -scheme IOSAutomationServer -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:IOSAutomationServerTests
git diff --check
```

Expected: every Gradle/XCTest command reports success and `git diff --check` prints nothing.

- [ ] **Step 2: Review behavior against both acceptance sets**

Confirm every issue #50 acceptance criterion and every section of `2026-09-09-ios-app-scope-and-element-tap-design.md` has a passing test. Confirm no new dependency, detekt baseline, lint baseline, or coverage-floor change exists. Inspect `git diff --stat` and `git status --short` for unrelated changes.

- [ ] **Step 3: Accept TD-010 after the implementation is in effect**

Change TD-010 and its index row from `proposed` to `accepted`. Because the `agentic` CLI is unavailable in this workspace, make only those two status edits manually.

- [ ] **Step 4: Commit verification metadata**

```bash
git add docs/decisions/TD-010-keep-wait-and-tap-atomic-in-native-servers.md docs/decisions/README.md
git commit -m "docs: accept native element tap decision"
```

- [ ] **Step 5: Run the completion review**

Invoke `agentico:task-review`, then `agentico:branch-review`, then `agentico:verification-before-completion`. Resolve findings with new RED/GREEN cycles and rerun the affected gate before reporting completion.
