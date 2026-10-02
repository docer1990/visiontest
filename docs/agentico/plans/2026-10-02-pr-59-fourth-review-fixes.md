# PR 59 Fourth Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development (recommended) or agentico:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reject MCP arguments whose JSON types disagree with their schemas and stop Android composed double tap after a rejected first tap.

**Architecture:** Enforce primitive types once in the shared `CallToolRequest` helpers so every MCP registration gets consistent validation before registrar access. Keep double-tap sequencing in its existing pure helper and short-circuit there before the delay.

**Tech Stack:** Kotlin, kotlinx.serialization JSON, kotlin.test, JUnit, MockK, Gradle

---

### Task 1: Enforce MCP primitive types

**Files:**
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/ToolDsl.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/tools/ToolDslTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrarTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/tools/IOSInteractionToolRegistrarTest.kt`

**Review:** checkpoint (changes the shared public MCP validation contract)

- [ ] **Step 1: Write the failing tests**

Update the `ToolDslTest` request fixture to accept typed `JsonElement` values. Preserve valid cases with `JsonPrimitive("hello")`, `JsonPrimitive(42)`, and `JsonPrimitive(true)`. Add wrong-type cases equivalent to:

```kotlin
@Test
fun `string helpers reject non-string JSON primitives`() {
    assertFailsWith<IllegalArgumentException> {
        request("key" to JsonPrimitive(123)).requireString("key")
    }
    assertFailsWith<IllegalArgumentException> {
        request("key" to JsonPrimitive(false)).optionalString("key")
    }
}

@Test
fun `integer helpers reject quoted JSON numbers`() {
    assertFailsWith<IllegalArgumentException> {
        request("x" to JsonPrimitive("42")).requireInt("x")
    }
    assertFailsWith<IllegalArgumentException> {
        request("x" to JsonPrimitive("7")).optionalInt("x")
    }
}

@Test
fun `boolean helper rejects quoted JSON booleans`() {
    assertFailsWith<IllegalArgumentException> {
        request("flag" to JsonPrimitive("true")).optionalBoolean("flag")
    }
}
```

Add one Android interaction handler test with a quoted integer such as `x: "10"` and one iOS interaction handler test with a non-string selector such as `targetText: 123`. Each test must assert an invalid-argument MCP result and zero backend requests.

Run `./gradlew :app:test --tests com.example.visiontest.tools.ToolDslTest --tests com.example.visiontest.tools.AndroidAutomationToolRegistrarTest --tests com.example.visiontest.tools.IOSInteractionToolRegistrarTest`. Expected: the new wrong-type tests fail because the current helpers coerce primitive content.

- [ ] **Step 2: Implement strict shared helpers**

In `ToolDsl.kt`, inspect the argument as a `JsonPrimitive` without converting it through another typed helper. Required and optional string helpers must require `isString`. Integer helpers must require a non-string primitive whose content parses exactly with `toIntOrNull()`. The boolean helper must require a non-string primitive whose content is `true` or `false`. Keep the existing missing-argument behavior and produce parameter-specific `IllegalArgumentException` messages for wrong types.

- [ ] **Step 3: Verify and commit**

Run `./gradlew :app:test --tests com.example.visiontest.tools.ToolDslTest --tests com.example.visiontest.tools.AndroidAutomationToolRegistrarTest --tests com.example.visiontest.tools.IOSInteractionToolRegistrarTest`. Expected: PASS with no failed tests.

```bash
git add app/src/main/kotlin/com/example/visiontest/tools/ToolDsl.kt app/src/test/kotlin/com/example/visiontest/tools/ToolDslTest.kt app/src/test/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrarTest.kt app/src/test/kotlin/com/example/visiontest/tools/IOSInteractionToolRegistrarTest.kt
git commit -m "fix: enforce MCP argument types"
```

### Task 2: Stop a rejected Android double tap

**Files:**
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/AndroidInteractionActions.kt`
- Modify: `automation-server/src/test/java/com/example/automationserver/uiautomator/ElementInteractionWaitTest.kt`

**Review:** checkpoint (final task)

- [ ] **Step 1: Write the failing test**

Add this behavior test to `ElementInteractionWaitTest`:

```kotlin
@Test
fun `composed double tap stops when first tap is rejected`() {
    val events = mutableListOf<String>()

    val result = performComposedDoubleTap(
        tap = {
            events += "tap"
            false
        },
        sleepMs = { events += "sleep" },
    )

    assertFalse(result)
    assertEquals(listOf("tap"), events)
}
```

Run `./gradlew :automation-server:testDebugUnitTest --tests com.example.automationserver.uiautomator.ElementInteractionWaitTest`. Expected: the new test fails because the helper sleeps and invokes the second tap.

- [ ] **Step 2: Implement the short circuit**

Change `performComposedDoubleTap` so it returns `false` immediately if the first `tap()` call returns `false`. Only a successful first tap may be followed by the 100 ms delay and the second tap. Preserve the second tap's boolean result.

- [ ] **Step 3: Verify and commit**

Run `./gradlew :automation-server:testDebugUnitTest --tests com.example.automationserver.uiautomator.ElementInteractionWaitTest`. Expected: PASS with no failed tests.

Run `./gradlew :app:test :app:e2eTest build` and `git diff --check`. Expected: both commands exit 0.

```bash
git add automation-server/src/main/java/com/example/automationserver/uiautomator/AndroidInteractionActions.kt automation-server/src/test/java/com/example/automationserver/uiautomator/ElementInteractionWaitTest.kt
git commit -m "fix: stop rejected Android double taps"
```

### Task 3: Cover Android interaction dispatch

**Files:**
- Create: `automation-server/src/main/java/com/example/automationserver/uiautomator/AndroidInteractionJsonRpcDispatcher.kt`
- Create: `automation-server/src/test/java/com/example/automationserver/uiautomator/AndroidInteractionJsonRpcDispatcherTest.kt`
- Modify: `automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt`

**Review:** checkpoint (final task)

- [ ] **Step 1: Write the failing dispatcher tests**

Define tests against this planned interface:

```kotlin
internal interface AndroidInteractionJsonRpcOperations {
    fun pressKey(keyCode: Int): OperationResult
    fun clearText(): OperationResult
    fun longPress(request: NativeGestureRequest): OperationResult
    fun doubleTap(request: NativeGestureRequest): OperationResult
    fun inputText(request: TargetedInputRequest): OperationResult
}

internal fun dispatchAndroidInteractionMethod(
    method: String,
    params: JsonObject?,
    operations: AndroidInteractionJsonRpcOperations,
): OperationResult?
```

Use a recording fake and typed assertions to verify these mappings:

```kotlin
private sealed interface OperationCall {
    data class PressKey(val keyCode: Int) : OperationCall
    data object ClearText : OperationCall
    data class LongPress(val request: NativeGestureRequest) : OperationCall
    data class DoubleTap(val request: NativeGestureRequest) : OperationCall
    data class InputText(val request: TargetedInputRequest) : OperationCall
}

assertEquals(OperationCall.PressKey(66), dispatch("ui.pressKey", """{"keyCode":66}"""))
assertEquals(OperationCall.ClearText, dispatch("ui.clearText", null))
assertEquals(
    OperationCall.LongPress(NativeGestureRequest(NativeGestureTarget.Coordinates(10, 20))),
    dispatch("ui.longPress", """{"x":10,"y":20}"""),
)
assertEquals("menu", dispatchedDoubleTapElement.selectors.resourceId)
assertEquals("name", dispatchedInput.selectors?.resourceId)
assertNull(dispatchAndroidInteractionMethod("ui.dumpHierarchy", null, operations))
```

Each fake operation must return a distinct `OperationResult`, and each handled dispatch must return that same value. Include selector `ui.doubleTap` parameters with `timeoutMs` and targeted `ui.inputText` parameters with `targetResourceId` and `timeoutMs`.

Run `./gradlew :automation-server:testDebugUnitTest --tests com.example.automationserver.uiautomator.AndroidInteractionJsonRpcDispatcherTest`. Expected: test compilation fails because the dispatcher and operations interface do not exist.

- [ ] **Step 2: Implement and wire the pure dispatcher**

Create the interface and function with the signatures above. Return `null` only for unrecognized methods. For the five recognized methods, call `parseKeyRequest`, `requireNoParams`, `parseGestureRequest`, or `parseTargetedInputRequest` before invoking the matching operation.

In `JsonRpcServerInstrumented`, add one adapter from `UiAutomatorBridgeInstrumented` to `AndroidInteractionJsonRpcOperations`. At the start of `executeMethod`, call the pure dispatcher. Translate `IllegalArgumentException` to the existing `InvalidParamsException`, return a handled `OperationResult`, and leave unrelated routes in the existing `when`. Remove the five duplicate interaction branches from that `when`.

- [ ] **Step 3: Verify and commit**

Run `./gradlew :automation-server:testDebugUnitTest --tests com.example.automationserver.uiautomator.AndroidInteractionJsonRpcDispatcherTest`. Expected: PASS with no failed tests.

Run `./gradlew :automation-server:compileDebugAndroidTestKotlin`, then run the repository gate `./gradlew :app:test :app:e2eTest build`. Run `git diff --check`. Expected: all commands exit 0.

```bash
git add automation-server/src/main/java/com/example/automationserver/uiautomator/AndroidInteractionJsonRpcDispatcher.kt automation-server/src/test/java/com/example/automationserver/uiautomator/AndroidInteractionJsonRpcDispatcherTest.kt automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt
git commit -m "test: cover Android interaction dispatch"
```
