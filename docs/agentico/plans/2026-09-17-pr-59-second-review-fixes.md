# PR 59 second review fixes plan

**Goal:** Reject unsupported Android key scope and malformed native JSON-RPC
parameter shapes before any operation reaches a backend.

**Architecture:** Keep validation at each public boundary. MCP handlers reject
Android app scope before registrar access. Native dispatchers preserve raw
parameter shape until they have distinguished absent parameters from an object
and from an invalid array, scalar, or explicit null.

**Testing:** Use plain TDD. Each task starts with focused tests that fail on the
current branch, applies the smallest boundary fix, and runs the affected suite.

---

## Task 1: Close Android validation gaps

**Files:**

- Modify: `app/src/test/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrarTest.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/AndroidInteractionToolRegistration.kt`
- Modify: `automation-server/src/test/java/com/example/automationserver/uiautomator/InteractionValidationTest.kt`
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/InteractionValidation.kt`
- Modify: `automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt`

**Review:** checkpoint

- [ ] **Step 1: Add failing MCP and native validation tests**

Extend `interaction handlers reject unsupported arguments before backend
access` to capture `android_press_key` and assert that a request containing
`bundleId` throws before the handler reaches the registrar.

Add parser tests with these assertions:

```kotlin
assertFailsWith<IllegalArgumentException> {
    parseKeyRequest(json("""{"keyCode":66,"bundleId":"app.id"}"""))
}
assertFailsWith<IllegalArgumentException> {
    requireObjectParams(JsonParser.parseString("[1]"), "ui.clearText")
}
assertNull(requireObjectParams(null, "ui.clearText"))
```

Run:

```bash
./gradlew :app:test --tests '*AndroidAutomationToolRegistrarTest.interaction handlers reject unsupported arguments before backend access'
./gradlew :automation-server:testDebugUnitTest --tests '*InteractionValidationTest*'
```

Expected: the new assertions fail because key scope is ignored and no raw
parameter-shape validator exists.

- [ ] **Step 2: Implement Android boundary validation**

In the MCP handler, call `rejectArguments(setOf("bundleId"), ...)` before
extracting the key input.

Add this native helper and use it before `executeMethod`:

```kotlin
fun requireObjectParams(params: JsonElement?, method: String): JsonObject? {
    require(params == null || params.isJsonObject) {
        "'$method' parameters must be an object"
    }
    return params?.asJsonObject
}
```

Treat explicit JSON null as a present invalid value. Add the `bundleId`
rejection to `parseKeyRequest`. Keep valid key requests and absent parameters
unchanged.

- [ ] **Step 3: Verify and commit**

Run:

```bash
./gradlew :app:test --tests '*AndroidAutomationToolRegistrarTest*'
./gradlew :automation-server:testDebugUnitTest --tests '*InteractionValidationTest*'
./gradlew :automation-server:compileDebugAndroidTestKotlin
git diff --check
```

Expected: all commands pass.

Commit:

```bash
git add app/src/main/kotlin/com/example/visiontest/tools/AndroidInteractionToolRegistration.kt app/src/test/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrarTest.kt automation-server/src/main/java/com/example/automationserver/uiautomator/InteractionValidation.kt automation-server/src/test/java/com/example/automationserver/uiautomator/InteractionValidationTest.kt automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt
git commit -m "fix: reject malformed Android interaction requests"
```

## Task 2: Preserve and validate iOS parameter shape

**Files:**

- Modify: `ios-automation-server/IOSAutomationServerUITests/Models/JsonRpcModels.swift`
- Modify: `ios-automation-server/IOSAutomationServerUITests/Server/JsonRpcServer.swift`
- Modify: `ios-automation-server/IOSAutomationServerTests/JsonRpcModelsTests.swift`
- Modify: `ios-automation-server/IOSAutomationServerUITests/AutomationServerUITest.swift`

**Review:** checkpoint

- [ ] **Step 1: Add failing parser and route tests**

Add a parser test that supplies `params: [1]` and proves the request preserves
an invalid parameter shape instead of treating it as absent. Add a finite UI
route test that sends the same shape to `ui.dismissKeyboard` and expects JSON-RPC
error `INVALID_PARAMS` with no result.

Run:

```bash
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerTests/JsonRpcModelsTests
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testInteractionJsonRpcRoutesRejectNonObjectParameters
```

Expected: the parser loses the invalid shape and the route executes dismissal
instead of returning `INVALID_PARAMS`.

- [ ] **Step 2: Implement iOS shape preservation**

Represent parsed parameters with an internal state that distinguishes absent,
object, and invalid shapes. `JsonRpcServer.handleRequest` must reject the invalid
state before `executeMethod`. It must continue passing `nil` for absent params
and the dictionary for object params. Arrays, scalars, and explicit JSON null
must return `INVALID_PARAMS`.

- [ ] **Step 3: Verify and commit**

Run:

```bash
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerTests
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testInteractionJsonRpcRoutesRejectNonObjectParameters
git diff --check
```

Expected: 97 iOS unit tests and the finite UI regression pass.

Commit:

```bash
git add ios-automation-server/IOSAutomationServerUITests/Models/JsonRpcModels.swift ios-automation-server/IOSAutomationServerUITests/Server/JsonRpcServer.swift ios-automation-server/IOSAutomationServerTests/JsonRpcModelsTests.swift ios-automation-server/IOSAutomationServerUITests/AutomationServerUITest.swift
git commit -m "fix: reject malformed iOS interaction parameters"
```

## Task 3: Verify the branch and update PR feedback

**Files:** none expected

**Review:** checkpoint

- [ ] **Step 1: Run the repository gates**

Run the Gradle app tests, E2E tests, full build, iOS unit suite, all finite iOS
interaction UI tests, and `git diff --check` as required by `AGENTS.md`.

- [ ] **Step 2: Run an independent branch review**

Review the final diff from `main` with the four blockers as deferred findings.
The review must find no open blocker before push.

- [ ] **Step 3: Push and answer the review**

Push the feature branch. Reply in the existing PR review context with the commit
and test evidence for the four fixes. For unsupported requests, cite the pinned
UIAutomator source or repository architecture that disproves the claim. Report
the nonblocking findings as deferred rather than claiming they were fixed.
