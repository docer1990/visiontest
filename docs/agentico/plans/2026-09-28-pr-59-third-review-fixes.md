# PR 59 Third Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development (recommended) or agentico:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route unscoped iOS interactions only to a foreground application and recognize Android custom editable controls before targeted input taps them.

**Architecture:** Filter XCTest-discovered application proxies by `runningForeground` before the existing fallback chain. Read Android editability from the accessibility node owned by the selected `UiObject2`, as recorded in TD-014, and inject the editability check into the focused interaction class for deterministic unit tests.

**Tech Stack:** Swift, XCTest, Kotlin, Android UIAutomator 2.3.0, AccessibilityNodeInfo, JUnit, MockK.

---

### Task 1: Select the foreground iOS application

**Files:**

- Modify: `ios-automation-server/IOSAutomationServerUITests/Helpers/Helpers.swift`
- Modify: `ios-automation-server/IOSAutomationServerUITests/Bridge/XCUITestBridge.swift`
- Test: `ios-automation-server/IOSAutomationServerTests/HelpersTests.swift`

**Review:** checkpoint

- [ ] **Step 1: Write the failing unit tests**

Add tests that define background-first candidates and prove that selection returns
the later foreground candidate. Add a second test that proves an all-background
list returns `nil` so the existing cached and system fallbacks can run.

```swift
func testForegroundApplicationSelectionSkipsBackgroundCandidates() {
    let candidates = ["background", "foreground"]

    let selected = firstForegroundApplication(in: candidates) { $0 == "foreground" }

    XCTAssertEqual(selected, "foreground")
}

func testForegroundApplicationSelectionReturnsNilWithoutForegroundCandidate() {
    XCTAssertNil(firstForegroundApplication(in: ["one", "two"]) { _ in false })
}
```

Run:

```bash
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerTests/HelpersTests
```

Expected: compilation fails because `firstForegroundApplication` does not exist.

- [ ] **Step 2: Implement foreground-only discovery**

Add this internal helper to `Helpers.swift`:

```swift
func firstForegroundApplication<T>(
    in applications: [T],
    isForeground: (T) -> Bool
) -> T? {
    applications.first(where: isForeground)
}
```

Change `discoverActiveApplication()` to map every non-system active element to a
monitored `XCUIApplication`, then return:

```swift
return firstForegroundApplication(in: applications) {
    $0.state == .runningForeground
}
```

Do not return the first monitored application before inspecting its state. Keep
the explicit target, cached foreground application, and SpringBoard fallback order
in `interactionTarget(bundleId:)` unchanged.

- [ ] **Step 3: Verify and commit**

Run:

```bash
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerTests/HelpersTests
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testSelectorGestureWithoutBundleIdUsesForegroundApplication
git diff --check
```

Expected: all selected tests pass and the diff check is clean.

Commit:

```bash
git add ios-automation-server/IOSAutomationServerUITests/Helpers/Helpers.swift ios-automation-server/IOSAutomationServerUITests/Bridge/XCUITestBridge.swift ios-automation-server/IOSAutomationServerTests/HelpersTests.swift
git commit -m "fix: select the foreground iOS application"
```

### Task 2: Read Android editability from the selected native node

**Files:**

- Create: `automation-server/src/main/java/com/example/automationserver/uiautomator/UiObject2Accessibility.kt`
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/AndroidInteractionActions.kt`
- Modify: `automation-server/src/test/java/com/example/automationserver/uiautomator/BaseUiAutomatorInteractionTest.kt`
- Create: `automation-server/src/test/java/com/example/automationserver/uiautomator/AndroidInteractionActionsTest.kt`
- Create: `automation-server/src/test/java/com/example/automationserver/uiautomator/UiObject2AccessibilityTest.kt`

**Review:** checkpoint

- [ ] **Step 1: Write failing editability and accessor tests**

Move the targeted-input readiness tests from
`BaseUiAutomatorInteractionTest.kt` into `AndroidInteractionActionsTest.kt` and
construct `AndroidInteractionActions` with an injected editability function.
Cover these cases:

```kotlin
@Test
fun `targeted input accepts an editable custom control`() {
    val element = readyElement(className = "example.CustomInput")
    val focusedNode = editableFocusedNode(inputAccepted = true)
    every { device.findObject(selector) } returns element
    every { element.isFocusable } returns true
    every { element.click() } returns Unit
    every { element.isFocused } returns true
    every { automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns focusedNode
    val actions = interactionActions(isEditable = { candidate -> candidate === element })

    val result = actions.targetedInput(targetedInputRequest())

    assertTrue(result.success)
    verify(exactly = 1) { element.click() }
    verify(exactly = 1) {
        focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any())
    }
}

@Test
fun `targeted input does not tap a noneditable custom control`() {
    val element = readyElement(className = "example.CustomInput")
    every { device.findObject(selector) } returns element
    every { element.isFocusable } returns true
    val actions = interactionActions(isEditable = { false })

    val result = actions.targetedInput(targetedInputRequest())

    assertFalse(result.success)
    verify(exactly = 0) { element.click() }
}

@Test
fun `editability access failure propagates before tap`() {
    val element = readyElement(className = "example.CustomInput")
    every { device.findObject(selector) } returns element
    every { element.isFocusable } returns true
    val actions = interactionActions(isEditable = { error("node unavailable") })

    val failure = assertFailsWith<IllegalStateException> {
        actions.targetedInput(targetedInputRequest())
    }

    assertEquals("node unavailable", failure.message)
    verify(exactly = 0) { element.click() }
}
```

Define `selector` as a class-level `mockk<BySelector>()`. Define
`interactionActions` with the existing mocked `device` and `automation`, a
`Rect(0, 0, 1080, 1920)` display, `selectorBuilder = { selector }`,
`selectorDescription = { "resourceId=name" }`, and the supplied `isEditable`
function. Reuse the existing `readyElement`, `editableFocusedNode`, and request
fixtures when moving them to this focused test class.

Add `UiObject2AccessibilityTest` that calls the accessor-resolution function and
asserts that UIAutomator 2.3.0 provides a zero-argument method named
`getAccessibilityNodeInfo` returning `AccessibilityNodeInfo`.

```kotlin
@Test
fun `pinned UiObject2 exposes the expected node accessor`() {
    val accessor = resolveUiObject2NodeAccessor()

    assertEquals("getAccessibilityNodeInfo", accessor.name)
    assertEquals(0, accessor.parameterCount)
    assertEquals(AccessibilityNodeInfo::class.java, accessor.returnType)
}
```

Run:

```bash
./gradlew :automation-server:testDebugUnitTest --tests '*AndroidInteractionActionsTest*' --tests '*UiObject2AccessibilityTest*'
```

Expected: compilation fails because the editability function parameter and
accessor-resolution function do not exist.

- [ ] **Step 2: Implement native-node editability**

Add these internal interfaces in `UiObject2Accessibility.kt`:

```kotlin
internal fun resolveUiObject2NodeAccessor(): Method

internal fun UiObject2.isAccessibilityEditable(): Boolean
```

`resolveUiObject2NodeAccessor()` must resolve the zero-argument private method
`getAccessibilityNodeInfo`, verify that its return type is
`AccessibilityNodeInfo`, make it accessible, and return it. Cache the resulting
`Method` once. `isAccessibilityEditable()` invokes that cached method on the
selected `UiObject2` and reads `isEditable`. It must not recycle the returned
node because `UiObject2` owns it. If invocation wraps a native failure, rethrow
the original cause when it is a runtime exception; otherwise throw an
`IllegalStateException` with the original cause.

Add this constructor parameter to `AndroidInteractionActions`:

```kotlin
private val isEditable: (UiObject2) -> Boolean = UiObject2::isAccessibilityEditable,
```

Replace the class-name suffix check in `findCandidate` with
`element.isFocusable && isEditable(element)`. Remove
`isEditableControlClass()`. Keep visibility and enabled checks unchanged.

- [ ] **Step 3: Verify and commit**

Run:

```bash
./gradlew :automation-server:testDebugUnitTest --tests '*AndroidInteractionActionsTest*' --tests '*UiObject2AccessibilityTest*' --tests '*BaseUiAutomatorInteractionTest*'
./gradlew :automation-server:detekt
./gradlew :automation-server:compileDebugAndroidTestKotlin
git diff --check
```

Expected: all selected tests, Detekt, Android test compilation, and the diff
check pass.

Commit:

```bash
git add automation-server/src/main/java/com/example/automationserver/uiautomator/UiObject2Accessibility.kt automation-server/src/main/java/com/example/automationserver/uiautomator/AndroidInteractionActions.kt automation-server/src/test/java/com/example/automationserver/uiautomator/BaseUiAutomatorInteractionTest.kt automation-server/src/test/java/com/example/automationserver/uiautomator/AndroidInteractionActionsTest.kt automation-server/src/test/java/com/example/automationserver/uiautomator/UiObject2AccessibilityTest.kt
git commit -m "fix: inspect Android element editability"
```

### Task 3: Verify and answer the third review

**Files:** none expected

**Review:** checkpoint

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
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testAlertResultSurvivesDismissal \
  -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testSelectorGestureWithoutBundleIdUsesForegroundApplication \
  -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testInteractionJsonRpcRoutesUseTheSharedTargetResolver \
  -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testInteractionJsonRpcRoutesRejectInvalidParameters \
  -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testInteractionJsonRpcRoutesRejectNonObjectParameters
git diff --check
```

Expected: every command passes. `testRunAutomationServer` remains excluded because
it intentionally serves forever.

- [ ] **Step 2: Review the branch**

Run an independent branch review from `main`. Pass both third-review findings as
deferred findings so the reviewer must confirm that each appears exactly once as
fixed, still open, or dismissed with evidence. Do not proceed with an open Must
Fix finding.

- [ ] **Step 3: Publish the fixes**

Push `feat/issue-40-missing-interactions` after explicit external-publication
authorization. Reply to inline comment `4124529465` in its thread with the iOS
fix commit and focused test evidence. Add a concise top-level PR comment for the
Android previously-missed finding because GitHub did not create an inline thread
for it. Include the Android fix commit and focused test evidence.
