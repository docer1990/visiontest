# iOS Automation Client Member Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development (recommended) or agentico:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose `swipeOnElement` as a regular `IOSAutomationClient` member without changing its wire contract.

**Architecture:** Keep iOS JSON-RPC operations on the client facade. Cover access from an external package and use the existing Detekt baseline for the facade's intentional method count.

**Tech Stack:** Kotlin, MockWebServer, Gradle, Detekt

---

### Task 1: Reproduce the public API inconsistency

**Files:**
- Create: `app/src/test/kotlin/com/example/visiontest/consumer/IOSAutomationClientPublicApiTest.kt`

- [x] Add a test in a consumer package that imports only `IOSAutomationClient` and `IOSElementSelectors`, invokes `client.swipeOnElement(...)`, and verifies the JSON-RPC method.
- [x] Run `./gradlew :app:test --tests com.example.visiontest.consumer.IOSAutomationClientPublicApiTest` and confirm compilation fails with `Unresolved reference 'swipeOnElement'`.

### Task 2: Make the operation a client member

**Files:**
- Modify: `app/src/main/kotlin/com/example/visiontest/ios/IOSAutomationClient.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt`
- Modify: `app/detekt-baseline.xml`

- [x] Move the existing function body inside `IOSAutomationClient` without changing its signature or payload construction.
- [x] Remove `import com.example.visiontest.ios.swipeOnElement` from the registrar.
- [x] Add `TooManyFunctions:IOSAutomationClient.kt$IOSAutomationClient : JsonRpcHttpClient` to the targeted Detekt baseline.
- [x] Run the consumer, serialization, registrar, and CLI parity tests and confirm they pass.
- [x] Run `./gradlew build --rerun-tasks` and `git diff --check`.

### Task 3: Publish and close the review thread

**Files:**
- No additional repository files.

- [ ] Commit the implementation and verification test.
- [ ] Push `feat/p3-cli-mcp-parity`.
- [ ] Reply to review comment `3957072938` with the change and validation results.
- [ ] Confirm the refreshed PR checks pass.
