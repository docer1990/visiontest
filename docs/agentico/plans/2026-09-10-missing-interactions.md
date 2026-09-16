# Missing interactions implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development (recommended) or agentico:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the keyboard, alert, long-press, double-tap, and targeted text-input operations accepted in issue #40, excluding pinch and zoom.

**Architecture:** CLI and MCP facades call shared registrar operations under TD-003. Kotlin clients send explicit JSON-RPC requests to native servers. Selector-based operations wait for an actionable native element and perform the interaction within one request under TD-010. Long press and double tap use the single-command targeting contract from TD-012.

**Tech stack:** Kotlin/JVM, Clikt, MCP Kotlin SDK, OkHttp MockWebServer, Android UIAutomator, Gson, Swift, XCTest, and XCUITest.

**Design:** `docs/agentico/specs/2026-09-10-missing-interactions-design.md`

---

### Task 1: Android client wire contracts

**Files:**

- Modify: `app/src/main/kotlin/com/example/visiontest/android/AutomationClient.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/android/AutomationClientTest.kt`

- [ ] **Step 1: Write the failing client tests**

Add tests that capture the MockWebServer request for numeric and named `ui.pressKey`, `ui.clearText`, coordinate and selector forms of `ui.longPress` and `ui.doubleTap`, and targeted `ui.inputText`. Pin backward compatibility with a focused-input test that sends only `text`.

```kotlin
@Test
fun `targeted input serializes selectors and timeout`() = runBlocking {
    client.inputText(
        text = "Ada",
        selectors = AndroidElementSelectors(resourceId = "name"),
        timeoutMs = 1_500,
    )

    val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
    assertEquals("ui.inputText", body["method"].asString)
    assertEquals("Ada", body["params"].asJsonObject["text"].asString)
    assertEquals("name", body["params"].asJsonObject["targetResourceId"].asString)
    assertEquals(1_500, body["params"].asJsonObject["timeoutMs"].asInt)
}
```

Expected failure: the new client members and extended `inputText` signature do not exist.

- [ ] **Step 2: Implement the Android client methods**

Add these public members. Element overloads serialize selectors with `target*` names only for `inputText`; gesture selectors retain the existing selector names.

```kotlin
suspend fun pressKey(keyCode: Int): String
suspend fun pressKey(action: String): String
suspend fun clearText(): String
suspend fun longPress(x: Int, y: Int): String
suspend fun longPress(selectors: AndroidElementSelectors, timeoutMs: Int): String
suspend fun doubleTap(x: Int, y: Int): String
suspend fun doubleTap(selectors: AndroidElementSelectors, timeoutMs: Int): String
suspend fun inputText(
    text: String,
    selectors: AndroidElementSelectors? = null,
    timeoutMs: Int? = null,
): String
```

Client methods only serialize already validated values. Preserve the existing read timeout behavior and existing focused-input request shape.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew :app:test --tests com.example.visiontest.android.AutomationClientTest`

Expected: PASS with no new warnings.

```bash
git add app/src/main/kotlin/com/example/visiontest/android/AutomationClient.kt app/src/test/kotlin/com/example/visiontest/android/AutomationClientTest.kt
git commit -m "feat: add Android interaction client methods"
```

### Task 2: Android native validation and keyboard operations

**Files:**

- Create: `automation-server/src/main/java/com/example/automationserver/uiautomator/InteractionValidation.kt`
- Create: `automation-server/src/test/java/com/example/automationserver/uiautomator/InteractionValidationTest.kt`
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/UiAutomatorModels.kt`
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/BaseUiAutomatorBridge.kt`
- Modify: `automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt`

- [ ] **Step 1: Write failing pure validation and dispatch tests**

Cover the five named key actions, nonnegative numeric key codes, rejection of an unknown action, strict integer parsing, missing or mixed gesture targets, blank selectors, timeout bounds, `timeoutMs` without targeted input, and the unchanged focused-input shape. Extend the instrumented dispatch test table with the new method names and parameter mappings.

```kotlin
@Test
fun `named keys distinguish backspace and forward delete`() {
    assertEquals(KeyEvent.KEYCODE_DEL, parseNamedKeyAction("backspace"))
    assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, parseNamedKeyAction("delete"))
}

@Test
fun `gesture request rejects coordinates mixed with selectors`() {
    val params = JsonObject().apply {
        addProperty("x", 10)
        addProperty("y", 20)
        addProperty("text", "Menu")
    }
    assertFailsWith<IllegalArgumentException> { parseGestureRequest(params) }
}
```

Expected failure: validation functions and JSON-RPC cases are absent.

- [ ] **Step 2: Implement validation and keyboard operations**

Define focused request types in `InteractionValidation.kt`.

```kotlin
sealed interface NativeGestureTarget {
    data class Coordinates(val x: Int, val y: Int) : NativeGestureTarget
    data class Element(val selectors: TapOnElementSelectors, val timeoutMs: Int) : NativeGestureTarget
}

data class NativeGestureRequest(val target: NativeGestureTarget)

data class TargetedInputRequest(
    val text: String,
    val selectors: TapOnElementSelectors? = null,
    val timeoutMs: Int? = null,
)

fun parseNamedKeyAction(action: String): Int
fun parseGestureRequest(params: JsonObject?): NativeGestureRequest
fun parseTargetedInputRequest(params: JsonObject?): TargetedInputRequest
```

Map `enter`, `tab`, `backspace`, `delete`, and `escape` to Android `KeyEvent` constants. Add bridge members `pressKey(keyCode: Int): OperationResult` and `clearText(): OperationResult`. `clearText` must require a focused editable node and use the native clear-text action. Add `ui.pressKey` and `ui.clearText` dispatch before changing gesture behavior.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew :automation-server:testDebugUnitTest`

Expected: PASS with the new validation tests.

```bash
git add automation-server/src/main/java/com/example/automationserver/uiautomator/InteractionValidation.kt automation-server/src/test/java/com/example/automationserver/uiautomator/InteractionValidationTest.kt automation-server/src/main/java/com/example/automationserver/uiautomator/UiAutomatorModels.kt automation-server/src/main/java/com/example/automationserver/uiautomator/BaseUiAutomatorBridge.kt automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt
git commit -m "feat: add Android keyboard interactions"
```

### Task 3: Android native gestures and targeted input

**Files:**

- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/InteractionValidation.kt`
- Modify: `automation-server/src/test/java/com/example/automationserver/uiautomator/InteractionValidationTest.kt`
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/BaseUiAutomatorBridge.kt`
- Create: `automation-server/src/main/java/com/example/automationserver/uiautomator/ElementInteractionWait.kt`
- Create: `automation-server/src/test/java/com/example/automationserver/uiautomator/ElementInteractionWaitTest.kt`
- Modify: `automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt`

- [ ] **Step 1: Write failing wait and behavior tests**

Extract a pure polling seam based on `ElementTapWait.kt`. Test immediate readiness, 500 ms cadence, readiness at the deadline, absent timeout, blocked timeout, and preservation of one timeout budget across target lookup, tap, focus acquisition, and input. Test that coordinate requests do not poll. Extend instrumented dispatch coverage for `ui.longPress`, `ui.doubleTap`, and both `ui.inputText` shapes.

```kotlin
@Test
fun `targeted input does not restart timeout after tap`() {
    val clock = FakeInteractionClock()
    val result = waitForTargetFocusAndInput(
        timeoutMs = 1_000,
        clock = ElementInteractionClock(clock::now, clock::advance),
        lookup = {
            if (clock.now() >= 500) {
                ElementInteractionCandidate("field", ElementInteractionReadiness.READY)
            } else {
                null
            }
        },
        tap = { },
        hasFocus = { false },
        input = { fail("must not type without focus") },
    )
    assertFalse(result.success)
    assertEquals(1_000, clock.now())
}

private class FakeInteractionClock {
    private var elapsedMs = 0L
    fun now(): Long = elapsedMs
    fun advance(durationMs: Long) { elapsedMs += durationMs }
}
```

Expected failure: the shared wait seam and native gesture methods do not exist.

- [ ] **Step 2: Implement atomic native interactions**

Add the production polling seam and bridge members:

```kotlin
enum class ElementInteractionReadiness { BLOCKED, READY }

data class ElementInteractionCandidate<T>(
    val element: T,
    val readiness: ElementInteractionReadiness,
)

data class ElementInteractionClock(
    val nowMs: () -> Long,
    val sleepMs: (Long) -> Unit,
)

fun <T> waitForTargetFocusAndInput(
    timeoutMs: Long,
    clock: ElementInteractionClock,
    lookup: () -> ElementInteractionCandidate<T>?,
    tap: (T) -> Unit,
    hasFocus: (T) -> Boolean,
    input: (T) -> Unit,
): ElementTapWaitResult

fun longPress(request: NativeGestureRequest): OperationResult
fun doubleTap(request: NativeGestureRequest): OperationResult
fun inputText(request: TargetedInputRequest): OperationResult
```

Keep the existing `inputText(text: String)` path as a delegate to an untargeted request. Selector targets must reuse `TapOnElementSelectors`, retain the ready element reference, and distinguish absent from blocked. Targeted input additionally requires an editable element, taps it, waits for focus inside the original deadline, then enters text. Hold long presses for 800 ms. Place Android double taps 100 ms apart. Validate coordinate bounds against the current display before injecting events.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew :automation-server:testDebugUnitTest`

Expected: PASS, including wait-boundary tests.

```bash
git add automation-server/src/main/java/com/example/automationserver/uiautomator/InteractionValidation.kt automation-server/src/test/java/com/example/automationserver/uiautomator/InteractionValidationTest.kt automation-server/src/main/java/com/example/automationserver/uiautomator/BaseUiAutomatorBridge.kt automation-server/src/main/java/com/example/automationserver/uiautomator/ElementInteractionWait.kt automation-server/src/test/java/com/example/automationserver/uiautomator/ElementInteractionWaitTest.kt automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt
git commit -m "feat: add Android targeted interactions"
```

### Task 4: Android registrar and MCP tools

**Files:**

- Create: `app/src/main/kotlin/com/example/visiontest/tools/InteractionTargetSupport.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/tools/InteractionTargetSupportTest.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrar.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrarTest.kt`

- [ ] **Step 1: Write failing registrar and schema tests**

Test validation before `requireServer`: exactly one key form, the five action names, coordinate pairs, selectors, mixed targets, blank selectors, timeout limits, and timeout without a targeted input selector. Capture registered schemas and verify required fields, integer bounds, descriptions, and the 45-second MCP execution timeout for waiting operations.

```kotlin
@Test
fun `longPress rejects mixed targets before server access`() = runBlocking {
    assertFailsWith<IllegalArgumentException> {
        registrar.longPress(10, 20, AndroidElementSelectors(text = "Menu"), 1_000)
    }
    assertEquals(0, server.requestCount)
}
```

Expected failure: registrar operations and MCP registrations are absent.

- [ ] **Step 2: Implement shared target validation and Android tools**

Add target validation functions to the focused support file and registrar members with these interfaces:

```kotlin
internal suspend fun pressKey(keyCode: Int?, action: String?): String
internal suspend fun clearText(): String
internal suspend fun longPress(
    x: Int?, y: Int?, selectors: AndroidElementSelectors, timeoutMs: Int?,
): String
internal suspend fun doubleTap(
    x: Int?, y: Int?, selectors: AndroidElementSelectors, timeoutMs: Int?,
): String
internal suspend fun inputText(
    text: String, selectors: AndroidElementSelectors? = null, timeoutMs: Int? = null,
): String
```

Register `android_press_key`, `android_clear_text`, `android_long_press`, and `android_double_tap`. Extend `android_input_text` without changing its required `text` field. Registrar validation must complete before the health check or JSON-RPC call.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew :app:test --tests com.example.visiontest.tools.InteractionTargetSupportTest --tests com.example.visiontest.tools.AndroidAutomationToolRegistrarTest`

Expected: PASS with no backend requests from invalid cases.

```bash
git add app/src/main/kotlin/com/example/visiontest/tools/InteractionTargetSupport.kt app/src/test/kotlin/com/example/visiontest/tools/InteractionTargetSupportTest.kt app/src/main/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrar.kt app/src/test/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrarTest.kt
git commit -m "feat: expose Android interaction tools"
```

### Task 5: iOS request models and pure interaction behavior

**Files:**

- Modify: `ios-automation-server/IOSAutomationServerUITests/Helpers/Helpers.swift`
- Modify: `ios-automation-server/IOSAutomationServerTests/HelpersTests.swift`
- Modify: `ios-automation-server/IOSAutomationServerUITests/Models/AutomationModels.swift`
- Modify: `ios-automation-server/IOSAutomationServerTests/AutomationModelsTests.swift`

- [ ] **Step 1: Write failing Swift tests**

Cover coordinate and selector gesture parsing, mixed-target rejection, strict timeout integers, target-prefixed input selectors, timeout without a target, alert actions and exact labels, nonblank bundle IDs, 500 ms polling, deadline boundaries, focus acquisition, keyboard disappearance verification, deterministic alert button choice, and `OperationResult.message` encoding.

```swift
func testAlertFallbackSelectsLastButtonForAcceptAndFirstForDismiss() throws {
    let buttons = [AlertButton(label: "Don't Allow", actionable: true),
                   AlertButton(label: "Allow", actionable: true)]
    XCTAssertEqual(selectAlertButton(buttons, action: .accept, label: nil)?.label, "Allow")
    XCTAssertEqual(selectAlertButton(buttons, action: .dismiss, label: nil)?.label, "Don't Allow")
}
```

Expected failure: the request types, selection helper, and optional message do not exist.

- [ ] **Step 2: Implement testable request and polling helpers**

Add `GestureRequest`, `TargetedInputRequest`, `AlertAction`, and `HandleAlertRequest` beside `ElementTapRequest`. Reuse `validatedString` and `strictInteger`. Add pure helpers for the interaction deadline, keyboard dismissal verification, and alert-button selection. Extend the result model without changing existing JSON output when `message` is nil.

```swift
struct GestureRequest {
    static let defaultTimeoutMs = 10_000
    static let maximumTimeoutMs = 30_000
    let target: GestureTarget
    init(params: [String: Any]?) throws
}

struct ElementSelectors {
    let text: String?
    let textContains: String?
    let identifier: String?
    let elementType: String?
    let label: String?
}

enum GestureTarget {
    case coordinates(CGPoint)
    case element(ElementSelectors, bundleId: String?, timeoutMs: Int)
}

struct TargetedInputRequest {
    let text: String
    let selectors: ElementSelectors?
    let bundleId: String?
    let timeoutMs: Int?
    init(params: [String: Any]?) throws
}

struct HandleAlertRequest {
    let action: AlertAction
    let buttonLabel: String?
    let bundleId: String?
    init(params: [String: Any]?) throws
}

struct AlertButton {
    let label: String
    let actionable: Bool
}

enum AlertAction: String {
    case accept
    case dismiss
}

func selectAlertButton(
    _ buttons: [AlertButton], action: AlertAction, label: String?
) -> AlertButton?

func performTargetedInput(
    request: TargetedInputRequest,
    now: () -> TimeInterval,
    wait: (TimeInterval) -> Void,
    readiness: () -> ElementTapReadiness,
    tap: () -> Void,
    hasFocus: () -> Bool,
    typeText: () -> Void
) -> OperationResult
```

- [ ] **Step 3: Verify and commit**

Run the iOS test command from `AGENTS.md` with `-only-testing:IOSAutomationServerTests`.

Expected: PASS with all helper and model tests.

```bash
git add ios-automation-server/IOSAutomationServerUITests/Helpers/Helpers.swift ios-automation-server/IOSAutomationServerTests/HelpersTests.swift ios-automation-server/IOSAutomationServerUITests/Models/AutomationModels.swift ios-automation-server/IOSAutomationServerTests/AutomationModelsTests.swift
git commit -m "feat: define iOS interaction contracts"
```

### Task 6: iOS native bridge and JSON-RPC dispatch

**Files:**

- Modify: `ios-automation-server/IOSAutomationServerUITests/Bridge/XCUITestBridge.swift`
- Modify: `ios-automation-server/IOSAutomationServerUITests/Server/JsonRpcServer.swift`
- Modify: `ios-automation-server/IOSAutomationServerTests/HelpersTests.swift`

- [ ] **Step 1: Write failing bridge-seam and dispatch tests**

Use the pure closures and protocols already used by element-tap tests. Verify that selector gestures keep one element reference, targeted input waits for a hittable editable element and focus, coordinate gestures skip lookup, keyboard dismissal swipes the keyboard and verifies disappearance, alert lookup checks the requested app before SpringBoard, exact labels override fallback ordering, and each JSON-RPC method constructs the validated request before bridge access.

```swift
func testTargetedInputTypesOnlyAfterFocus() throws {
    let request = try TargetedInputRequest(params: [
        "text": "Ada", "targetResourceId": "name", "timeoutMs": 1_000
    ])
    var elapsed = 0.0
    var events: [String] = []
    let result = performTargetedInput(
        request: request,
        now: { elapsed },
        wait: { elapsed += $0 },
        readiness: { .ready },
        tap: { events.append("tap") },
        hasFocus: { true },
        typeText: { events.append("type") }
    )
    XCTAssertTrue(result.success)
    XCTAssertEqual(events, ["tap", "type"])
}
```

Expected failure: bridge members and dispatch cases are absent.

- [ ] **Step 2: Implement native iOS operations**

Add these bridge members and JSON-RPC cases:

```swift
func longPress(_ request: GestureRequest) -> OperationResult
func doubleTap(_ request: GestureRequest) -> OperationResult
func inputText(_ request: TargetedInputRequest) -> OperationResult
func dismissKeyboard(bundleId: String?) -> OperationResult
func handleAlert(_ request: HandleAlertRequest) -> OperationResult
```

Use `press(forDuration: 0.8)` and `doubleTap()`. Selector lookup must use the existing app-scope and selector mapping. Targeted input uses one deadline and services the default-mode run loop. Keyboard dismissal uses `swipeDown()` on the visible keyboard and checks that it disappears. Alert lookup checks the scoped or active app, then `com.apple.springboard`; it taps an exact requested label or applies the accepted first/last fallback.

- [ ] **Step 3: Verify and commit**

Run the iOS test command from `AGENTS.md` with `-only-testing:IOSAutomationServerTests`.

Expected: PASS with no changes to existing tap timing tests.

```bash
git add ios-automation-server/IOSAutomationServerUITests/Bridge/XCUITestBridge.swift ios-automation-server/IOSAutomationServerUITests/Server/JsonRpcServer.swift ios-automation-server/IOSAutomationServerTests/HelpersTests.swift
git commit -m "feat: implement iOS native interactions"
```

### Task 7: iOS client, registrar, and MCP tools

**Files:**

- Modify: `app/src/main/kotlin/com/example/visiontest/ios/IOSAutomationClient.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/ios/IOSAutomationClientTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/consumer/IOSAutomationClientPublicApiTest.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/tools/IOSInteractionToolRegistrarTest.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/InteractionTargetSupport.kt`

- [ ] **Step 1: Write failing client, public API, registrar, and schema tests**

Capture all request shapes and omitted optionals. Verify public client members compile from the consumer package. Test registrar validation before server health checks, app scope mapping, alert action choices, exact labels, coordinate and selector targets, timeout bounds, and the unchanged focused-input call.

```kotlin
@Test
fun `handleAlert serializes action label and scope`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
    client.handleAlert("accept", "Allow", "com.example.app")
    val params = JsonParser.parseString(server.takeRequest().body.readUtf8())
        .asJsonObject["params"].asJsonObject
    assertEquals("accept", params["action"].asString)
    assertEquals("Allow", params["buttonLabel"].asString)
    assertEquals("com.example.app", params["bundleId"].asString)
}
```

Expected failure: iOS client and registrar members are absent.

- [ ] **Step 2: Implement iOS host operations**

Add these client members:

```kotlin
suspend fun longPress(x: Int, y: Int): String
suspend fun longPress(selectors: IOSElementSelectors, timeoutMs: Int): String
suspend fun doubleTap(x: Int, y: Int): String
suspend fun doubleTap(selectors: IOSElementSelectors, timeoutMs: Int): String
suspend fun dismissKeyboard(bundleId: String? = null): String
suspend fun handleAlert(action: String, buttonLabel: String? = null, bundleId: String? = null): String
suspend fun inputText(
    text: String,
    bundleId: String? = null,
    selectors: IOSElementSelectors? = null,
    timeoutMs: Int? = null,
): String
```

Add matching registrar operations. Register `ios_dismiss_keyboard`, `ios_handle_alert`, `ios_long_press`, and `ios_double_tap`. Extend `ios_input_text` with optional `target*` fields and `timeoutMs`. Apply bundle, target, action, and timeout validation before `requireServer`.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew :app:test --tests com.example.visiontest.ios.IOSAutomationClientTest --tests com.example.visiontest.consumer.IOSAutomationClientPublicApiTest --tests com.example.visiontest.tools.IOSInteractionToolRegistrarTest`

Expected: PASS with no backend requests for invalid inputs.

```bash
git add app/src/main/kotlin/com/example/visiontest/ios/IOSAutomationClient.kt app/src/test/kotlin/com/example/visiontest/ios/IOSAutomationClientTest.kt app/src/test/kotlin/com/example/visiontest/consumer/IOSAutomationClientPublicApiTest.kt app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt app/src/test/kotlin/com/example/visiontest/tools/IOSInteractionToolRegistrarTest.kt app/src/main/kotlin/com/example/visiontest/tools/InteractionTargetSupport.kt
git commit -m "feat: expose iOS interaction tools"
```

### Task 8: CLI commands and cross-facade parity

**Files:**

- Create: `app/src/main/kotlin/com/example/visiontest/cli/InteractionTargetOptions.kt`
- Create: `app/src/main/kotlin/com/example/visiontest/cli/commands/PressKeyCommand.kt`
- Create: `app/src/main/kotlin/com/example/visiontest/cli/commands/ClearTextCommand.kt`
- Create: `app/src/main/kotlin/com/example/visiontest/cli/commands/LongPressCommand.kt`
- Create: `app/src/main/kotlin/com/example/visiontest/cli/commands/DoubleTapCommand.kt`
- Create: `app/src/main/kotlin/com/example/visiontest/cli/commands/DismissKeyboardCommand.kt`
- Create: `app/src/main/kotlin/com/example/visiontest/cli/commands/HandleAlertCommand.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/commands/InputTextCommand.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/VisionTestCli.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/ParityCliTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/CliCommandIntegrationTest.kt`

- [ ] **Step 1: Write failing CLI parsing and parity tests**

Raise the expected subcommand set from 23 to 29. Test every supported platform route, exit code 5 for the four platform-specific commands on the other platform, positional numeric and named keys, gesture coordinate and selector forms, mixed and incomplete target exit code 2, target-prefixed input flags, iOS app scope, alert arguments, and validation before any backend request.

```kotlin
@Test
fun `long_press accepts an iOS selector and app scope`() {
    respond(iosServer, successResult)
    val result = invoke(
        ::LongPressCommand,
        "-p", "ios", "--resource-id", "menu", "--bundle-id", "com.example.app",
    )
    assertEquals(0, result.exitCode, result.stderr)
    val params = request(iosServer)["params"].asJsonObject
    assertEquals("menu", params["resourceId"].asString)
    assertEquals("com.example.app", params["bundleId"].asString)
}
```

Expected failure: command classes and registrations are absent.

- [ ] **Step 2: Implement CLI adapters**

`InteractionTargetOptions` owns `--x`, `--y`, the five standard selector options, optional iOS `--bundle-id`, and `--timeout`. Add a separate target-prefixed option group for `input_text`. Commands must adapt parsed values to registrar operations and must not duplicate JSON-RPC logic. `press_key` accepts one positional key. `handle_alert` accepts one `accept|dismiss` argument and optional `--button-label`.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew :app:test --tests com.example.visiontest.cli.ParityCliTest --tests com.example.visiontest.cli.CliCommandIntegrationTest`

Expected: PASS, with all 29 unique commands and correct pre-backend failures.

```bash
git add app/src/main/kotlin/com/example/visiontest/cli/InteractionTargetOptions.kt app/src/main/kotlin/com/example/visiontest/cli/commands/PressKeyCommand.kt app/src/main/kotlin/com/example/visiontest/cli/commands/ClearTextCommand.kt app/src/main/kotlin/com/example/visiontest/cli/commands/LongPressCommand.kt app/src/main/kotlin/com/example/visiontest/cli/commands/DoubleTapCommand.kt app/src/main/kotlin/com/example/visiontest/cli/commands/DismissKeyboardCommand.kt app/src/main/kotlin/com/example/visiontest/cli/commands/HandleAlertCommand.kt app/src/main/kotlin/com/example/visiontest/cli/commands/InputTextCommand.kt app/src/main/kotlin/com/example/visiontest/cli/VisionTestCli.kt app/src/test/kotlin/com/example/visiontest/cli/ParityCliTest.kt app/src/test/kotlin/com/example/visiontest/cli/CliCommandIntegrationTest.kt
git commit -m "feat: add missing interaction CLI commands"
```

### Task 9: Public contracts, MCP inventory, and compatibility guidance

**Files:**

- Modify: `app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt`
- Modify: `docs/agentico/specs/cli.md`
- Create: `docs/agentico/specs/missing-interactions.md`
- Modify: `CLAUDE.md`
- Modify: `README.md`
- Modify: `app/src/main/resources/agent-instructions.md`

- [ ] **Step 1: Write the failing packaged MCP contract assertions**

Add the eight new tool names to `EXPECTED_TOOLS`. Extend schema assertions for the mutually exclusive target rules enforced at runtime, target-prefixed input fields, alert actions, key forms, timeout bounds, and unchanged required input text.

```kotlin
assertTrue("android_long_press" in names)
assertTrue("android_double_tap" in names)
assertTrue("ios_long_press" in names)
assertTrue("ios_double_tap" in names)
assertEquals(setOf("text"), toolsByName.getValue("android_input_text").requiredFields())
```

Expected failure: the packaged server lacks the eight names and extended schemas.

- [ ] **Step 2: Update contracts and user guidance**

Update the CLI count and command table to 29. Write the observable contract from the approved design into `missing-interactions.md`, including readiness, one timeout budget, target validation, operation failures, alert ordering, named keys, and native artifact compatibility. Update all MCP and CLI tables. Keep `agent-instructions.md` self-contained and explain when agents should prefer selector targeting over coordinates. Add README examples for form submission and iOS permission handling.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew :app:e2eTest --tests com.example.visiontest.McpStdioE2ETest`

Expected: PASS with no missing or duplicate tools.

```bash
git add app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt docs/agentico/specs/cli.md docs/agentico/specs/missing-interactions.md CLAUDE.md README.md app/src/main/resources/agent-instructions.md
git commit -m "docs: publish missing interaction contracts"
```

### Task 10: Full verification and branch review

**Files:**

- No files are expected to change unless verification or review finds a branch-owned defect.

- [ ] **Step 1: Run the repository gates**

Run:

```bash
./gradlew :app:test
./gradlew :app:e2eTest
./gradlew build
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerTests
git diff --check
```

Expected: every command exits 0. Do not change coverage floors or regenerate lint or detekt baselines.

- [ ] **Step 2: Review the branch against issue #40**

Use `agentico:branch-review`. Confirm every accepted operation has native dispatch, a Kotlin client, a shared registrar operation, an MCP tool, a CLI adapter, executable tests, and compatibility documentation. Confirm pinch and zoom did not enter the branch.

- [ ] **Step 3: Fix branch-owned findings and commit**

Repeat the narrow failing test before the full gates. Stage only the exact files changed for a branch-owned finding, then commit them with `git commit -m "fix: address missing interaction review findings"`. If the review finds nothing, leave the branch without an empty commit.
