# PR 59 Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development (recommended) or agentico:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve every confirmed PR 59 review defect, restore the Android CI job, and update the review threads with verified results.

**Architecture:** Keep validation at each public boundary and keep registrars responsible for shared operation behavior. Add compatibility shims at the public client API, deterministic deadline and selection helpers in native code, and explicit Android SDK packages in every affected GitHub Actions workflow.

**Tech Stack:** Kotlin/JVM, Android UIAutomator, Kotlin MCP SDK, Swift/XCTest, Gradle, GitHub Actions

---

### Task 1: Preserve the Android client API and tighten MCP validation

**Files:**
- Modify: `app/src/main/kotlin/com/example/visiontest/android/AutomationClient.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/ToolDsl.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/AndroidInteractionToolRegistration.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/android/AutomationClientTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/tools/ToolDslTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrarTest.kt`

**Review:** checkpoint, because the public method descriptor and request validation are consumed by later integration tests.

- [ ] **Step 1: Write the failing compatibility and validation tests**

Add a reflection assertion for the legacy suspend descriptor and a test that rejects unknown Android scope before the registrar runs:

```kotlin
@Test
fun `legacy focused input descriptor remains available`() {
    assertTrue(
        AutomationClient::class.java.declaredMethods.any {
            it.name == "inputText" && it.parameterTypes.contentEquals(
                arrayOf(String::class.java, Continuation::class.java)
            )
        }
    )
}

@Test
fun `Android request rejects unsupported bundle scope`() {
    val request = CallToolRequest("android_long_press", buildJsonObject { put("bundleId", "app.id") })
    assertFailsWith<IllegalArgumentException> {
        request.rejectArguments(setOf("bundleId"), "Android interactions do not support bundleId")
    }
}
```

Extend the schema test to assert `keyCode.maximum == Int.MAX_VALUE`. Capture the clear-text, gesture, and input handlers and verify that `bundleId` or any clear-text argument produces an MCP error without backend requests.

Expected RED: the legacy descriptor is absent, the helper is undefined, and the key schema has no maximum.

- [ ] **Step 2: Implement the compatibility overload and boundary checks**

Restore this hidden overload and delegate to the extended method:

```kotlin
@Deprecated(
    message = "Use inputText with selectors and timeoutMs",
    level = DeprecationLevel.HIDDEN,
)
suspend fun inputText(text: String): String = inputText(text, selectors = null, timeoutMs = null)
```

Add a `CallToolRequest` helper that rejects named unsupported fields. Apply it to Android clear text, gestures, and targeted input before reading other arguments. Publish `maximum = Int.MAX_VALUE` in the key-code schema.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew :app:test --tests '*AutomationClientTest' --tests '*ToolDslTest' --tests '*AndroidAutomationToolRegistrarTest'`. Expected: PASS.

Commit: `fix: preserve Android interaction contracts`

### Task 2: Enforce Android JSON-RPC validation and actionable errors

**Files:**
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/InteractionValidation.kt`
- Modify: `automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt`
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/BaseUiAutomatorBridge.kt`
- Modify: `automation-server/src/test/java/com/example/automationserver/uiautomator/InteractionValidationTest.kt`
- Modify: `automation-server/src/test/java/com/example/automationserver/uiautomator/BaseUiAutomatorInteractionTest.kt`

**Review:** checkpoint, because native request parsing defines the JSON-RPC contract used by the remaining Android work.

- [ ] **Step 1: Write failing parser and result tests**

```kotlin
@Test
fun `Android interactions reject app scope and clear text parameters`() {
    assertFailsWith<IllegalArgumentException> {
        parseGestureRequest(json("""{"x":1,"y":2,"bundleId":"app.id"}"""))
    }
    assertFailsWith<IllegalArgumentException> {
        parseTargetedInputRequest(json("""{"text":"Ada","bundleId":"app.id"}"""))
    }
    assertFailsWith<IllegalArgumentException> {
        requireNoParams(json("""{"text":"Name"}"""), "ui.clearText")
    }
}

@Test
fun `pressKey reports a rejected native key action`() {
    every { device.pressKeyCode(66) } returns false
    val result = bridge.pressKey(66)
    assertFalse(result.success)
    assertEquals("Native key press failed", result.error)
}
```

Add assertions that noneditable and disabled focused nodes produce unambiguous errors.

Expected RED: parsers accept `bundleId`, clear text has no validator, false key presses omit errors, and existing messages use `and`.

- [ ] **Step 2: Implement validation and result messages**

Reject `bundleId` in `parseGestureRequest` and `parseTargetedInputRequest`. Add `requireNoParams(params, method)` and call it before `ui.clearText`. Use `params?.get("timeoutMs")` in the coordinate branch. Return `Native key press failed` when `pressKeyCode` returns false and report `not editable or not enabled` for node-state failures.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew :automation-server:testDebugUnitTest --tests '*InteractionValidationTest' --tests '*BaseUiAutomatorInteractionTest'`. Expected: PASS.

Commit: `fix: validate Android interaction requests`

### Task 3: Prevent Android targeted input from tapping noneditable elements and enforce its deadline

**Files:**
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/AndroidInteractionActions.kt`
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/ElementInteractionWait.kt`
- Modify: `automation-server/src/test/java/com/example/automationserver/uiautomator/BaseUiAutomatorInteractionTest.kt`
- Modify: `automation-server/src/test/java/com/example/automationserver/uiautomator/ElementInteractionWaitTest.kt`

**Review:** checkpoint, because this changes native interaction readiness and timeout semantics.

- [ ] **Step 1: Write failing readiness and post-input deadline tests**

```kotlin
@Test
fun `targeted input does not tap a noneditable selected element`() {
    val element = readyElement(className = "android.widget.Button")
    every { device.findObject(any<BySelector>()) } returns element
    val result = bridge.inputText(targetedInputRequest())
    assertFalse(result.success)
    assertTrue(result.error!!.contains("not actionable"))
    verify(exactly = 0) { element.click() }
}

@Test
fun `successful input that finishes after deadline times out`() {
    val clock = FakeClock()
    val result = waitForTargetFocusAndInput(
        timeoutMs = 1_000,
        description = "field",
        clock = ElementInteractionClock(clock::now, clock::sleep),
        operation = TargetedInputOperation(
            lookup = { ready("field") },
            tap = {},
            hasEditableFocus = { true },
            input = { clock.sleep(1_001); ElementTapWaitResult(success = true) },
        ),
    )
    assertFalse(result.success)
    assertTrue(result.error!!.contains("input within 1000ms"))
}
```

Add a companion test proving that a native input failure remains unchanged even if the clock passes the deadline.

Expected RED: the button is tapped and a late successful input remains successful.

- [ ] **Step 2: Implement editability readiness and the final deadline check**

Classify Android editable controls before returning `READY`, covering `EditText` and `AutoCompleteTextView` class families. After `operation.input`, return its failure unchanged; only convert a successful result to a timeout when `clock.nowMs() > deadline`.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew :automation-server:testDebugUnitTest --tests '*BaseUiAutomatorInteractionTest' --tests '*ElementInteractionWaitTest'`. Expected: PASS.

Commit: `fix: harden Android targeted input readiness`

### Task 4: Preserve iOS input deadlines and duplicate alert order

**Files:**
- Modify: `ios-automation-server/IOSAutomationServerUITests/Helpers/Helpers.swift`
- Modify: `ios-automation-server/IOSAutomationServerUITests/Bridge/XCUITestBridge.swift`
- Modify: `ios-automation-server/IOSAutomationServerTests/HelpersTests.swift`

**Review:** checkpoint, because the helper result feeds the XCUITest bridge.

- [ ] **Step 1: Write failing deterministic Swift tests**

```swift
func testTargetedInputTimesOutWhenSuccessfulTypingFinishesAfterDeadline() throws {
    let request = try TargetedInputRequest(params: [
        "text": "Ada", "targetResourceId": "name", "timeoutMs": 1_000
    ])
    var clock: TimeInterval = 0
    let result = performTargetedInput(
        request: request,
        now: { clock },
        readiness: { .ready },
        tap: {},
        hasFocus: { true },
        typeText: { clock = 1.001 }
    )
    XCTAssertFalse(result.success)
    XCTAssertTrue(result.error?.contains("input") == true)
}

func testAlertSelectionKeepsTheChosenDuplicateIndex() {
    let buttons = [
        AlertButton(label: "Allow", actionable: false),
        AlertButton(label: "Allow", actionable: true)
    ]
    XCTAssertEqual(selectAlertButtonIndex(buttons, action: .accept, label: "Allow"), 1)
}
```

Expected RED: late typing reports success and no index-preserving selector exists.

- [ ] **Step 2: Implement final deadline and index-preserving alert selection**

Check the clock immediately after `typeText()`. Return a timeout only after successful typing exceeds the deadline. Replace label-only alert selection with `selectAlertButtonIndex`, filter requested labels by actionability, and tap `buttons[index]` directly.

- [ ] **Step 3: Verify and commit**

Run the `IOSAutomationServerTests` scheme on the iPhone 17 simulator. Expected: PASS with no failures.

Commit: `fix: preserve iOS interaction deadlines and order`

### Task 5: Restore Android CI setup

**Files:**
- Modify: `.github/workflows/pull-request-tests.yaml`
- Modify: `.github/workflows/release.yaml`
- Modify: `.github/workflows/android-emulator-smoke.yaml`

**Review:** deferred, because no later implementation task consumes the workflow configuration.

- [ ] **Step 1: Confirm the failing configuration**

Record the existing GitHub Actions failure: `setup-android` invokes `sdkmanager tools` and exits with `Warning: Failed to find package 'tools'` before Gradle starts. Confirm every `setup-android` use lacks an explicit `packages` input.

- [ ] **Step 2: Configure the available SDK package explicitly**

Set this input on all four `android-actions/setup-android` steps:

```yaml
with:
  packages: platform-tools
```

Keep license acceptance and emulator provisioning unchanged.

- [ ] **Step 3: Verify and commit**

Run a YAML parse check and `rg -n -A2 'android-actions/setup-android' .github/workflows` to confirm all four steps specify `platform-tools`.

Commit: `ci: stop installing the removed Android tools package`

### Task 6: Run the full gate and update PR review threads

**Files:**
- Modify only if verification exposes a defect in the preceding tasks.

**Review:** checkpoint, final branch review and CI status determine whether the PR is ready.

- [ ] **Step 1: Run local verification**

Run:

```bash
./gradlew :app:test
./gradlew :app:e2eTest
./gradlew build
xcodebuild test -project ios-automation-server/IOSAutomationServer.xcodeproj -scheme IOSAutomationServer -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:IOSAutomationServerTests
git diff --check
```

Also run the three finite iOS UI tests for foreground-app resolution and JSON-RPC routes. Expected: every command passes and the worktree is clean after commits.

- [ ] **Step 2: Review the complete fix**

Run an independent branch review against `main`. Resolve any blocking finding with a new RED-GREEN cycle and repeat the affected gates.

- [ ] **Step 3: Push and respond to review**

Push the branch. Reply in each inline GitHub thread with the exact fix or the verified technical reason for keeping the current behavior. Confirm that the new PR checks start and inspect the Android job result.

Commit only if verification required additional changes.
