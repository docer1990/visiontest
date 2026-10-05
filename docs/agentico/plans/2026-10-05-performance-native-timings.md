# Native Performance Timings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development or agentico:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver #57B, correlated native stage timings with host-only fallback for existing servers.

**Architecture:** Optional HTTP headers carry bounded versioned timing metadata.
Each native request owns its timing context, and the Kotlin transport validates
and imports it without changing the JSON-RPC body. See [TD-015](../../decisions/TD-015-isolate-local-performance-measurements-from-operation-results.md)
and the [design](../specs/2026-10-05-performance-baseline-design.md).

**Tech Stack:** Existing Kotlin/Gson/Ktor and Swift/Foundation/Swifter; JUnit and XCTest.

**Depends on:** [57A](2026-10-05-performance-host-tracing.md). No new JSON-RPC method.

---

### Task 1: Specify and validate native metadata on the host

**Files:**
- Create: `app/src/main/kotlin/com/example/visiontest/performance/NativeTimingCodec.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/performance/NativeTimingCodecTest.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/common/JsonRpcHttpClient.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/android/AutomationClientTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/ios/IOSAutomationClientTest.kt`
- Modify: `docs/agentico/specs/performance-tracing.md`

**Review:** checkpoint. Both native implementations consume this protocol.

- [ ] Define `decodeNativeTiming(value: String?, invocationId: String,
  requestSpanId: String): NativeTimingResult` and
  `encodeTraceRequest(invocationId: String, requestSpanId: String): String`.
  `NativeTimingResult` has `status: NativeTimingStatus` and `spans: List<NativeSpan>`.
  Status is `AVAILABLE`, `MISSING`, `INVALID`, `MISMATCHED`, or `UNSUPPORTED`.
  `NativeSpan` uses the design's fields and a distinct native clock domain.
- [ ] Begin with valid, missing, mismatched, and invalid metadata tests.

```kotlin
@Test
fun `metadata for another request is rejected without throwing`() {
    val invocation = "a".repeat(32)
    val request = "b".repeat(32)
    val json = """{"version":1,"invocationId":"${"c".repeat(32)}","requestSpanId":"$request","spans":[],"missingStages":[]}"""
    val header = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.toByteArray(Charsets.UTF_8))
    assertEquals(
        NativeTimingStatus.MISMATCHED,
        decodeNativeTiming(header, invocation, request).status,
    )
    assertEquals(NativeTimingStatus.MISSING, decodeNativeTiming(null, invocation, request).status)
    assertEquals(NativeTimingStatus.INVALID, decodeNativeTiming("!", invocation, request).status)
}
```

- [ ] Validate before importing. Cap encoded size at 8 KiB and spans at 32;
  reject cycles, duplicate IDs, negative/overflowing durations, unknown stages,
  invalid metric types, and impossible parent intervals. Unknown protocol versions
  remain unsupported. Reject metadata without changing operation results.
- [ ] Send headers only for enabled traced requests. Use host request-span IDs,
  not JSON-RPC IDs, for correlation. Keep health checks separate and omit native
  timing expectations for `/health` in version 1.
- [ ] Test both platform clients against MockWebServer with absent and malformed
  headers, normal/failed JSON-RPC bodies, connection loss, and concurrent requests.
  Assert exact original body and one dispatch. No support probe or fallback retry.
- [ ] Document the exact native stage registry: `request`, `decode`, `queue_wait`,
  `operation`, `ui_sync`, `poll`, `poll_wait`, `screenshot_capture`, `png_encode`,
  `base64_encode`, `response_encode`, and `screenshot_capture_encode` for combined
  APIs. Map each to native records, not host `TraceStage` values with wrong clocks.
- [ ] Run `./gradlew :app:test --tests '*NativeTimingCodecTest' --tests '*AutomationClientTest' --tests '*IOSAutomationClientTest'`.
  Expect missing codec/header behavior at RED and unchanged result contracts at GREEN.
  Commit as `feat(performance): validate optional native timing metadata`.

### Task 2: Record native Android request and UI stages

**Files:**
- Create: `automation-server/src/main/java/com/example/automationserver/performance/NativeTimingRecorder.kt`
- Create: `automation-server/src/test/java/com/example/automationserver/performance/NativeTimingRecorderTest.kt`
- Modify: `automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt`
- Modify: `automation-server/src/androidTest/java/com/example/automationserver/UiAutomatorBridgeInstrumented.kt`
- Create: `automation-server/src/androidTest/java/com/example/automationserver/NativeTimingIntegrationTest.kt`
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/BaseUiAutomatorBridge.kt`
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/ElementTapWait.kt`
- Modify: `automation-server/src/main/java/com/example/automationserver/uiautomator/ElementInteractionWait.kt`
- Modify: `automation-server/src/test/java/com/example/automationserver/uiautomator/ElementTapWaitTest.kt`
- Modify: `automation-server/src/test/java/com/example/automationserver/uiautomator/ElementInteractionWaitTest.kt`

**Review:** checkpoint. Validate the protocol against Android before copying it to Swift.

- [ ] Add a pure recorder with injectable monotonic clock, fixed stage names,
  bounded spans, and aggregate metrics. Test its clock and header round trip first.

```kotlin
@Test
fun `native stage duration uses only its own clock`() {
    var now = 100L
    val recorder = NativeTimingRecorder("a".repeat(32), "b".repeat(32)) { now }
    recorder.measure("operation") { now = 140L }
    val spans = recorder.snapshot().spans
    assertEquals(40L, spans.single { it.stage == "operation" }.durationNs)
}
```

- [ ] Define `NativeTimingRecorder(invocationId: String, requestSpanId: String,
  nowNs: () -> Long = System::nanoTime)`, `fun <T> measure(stage: String, block: () -> T): T`,
  `snapshot(): NativeTimingSnapshot`, and `encodeHeader(): String?`. Snapshot
  contains immutable span values plus missing stages. Restrict strings to the
  documented registry. Add safe suspend scope support for Ktor boundaries.
- [ ] Parse the optional header before JSON decoding. Pass request ownership
  through Ktor coroutine context or explicit method parameters; do not attach a
  mutable recorder to the shared bridge. Instrument explicit idle waits, native
  selector polling, screenshot capture, PNG/base64 encoding, and serialization.
- [ ] Retain normal `call.respond` behavior when tracing is off. In traced mode,
  measure the same configured serialization, then attach the header to the frozen
  response. Test semantic equality, content type, HTTP status, and result framing.
- [ ] There is no assumed Android queue duration. Record `queue_wait` only if an
  actual scheduling boundary is measurable. Report SDK-internal synchronization
  as unavailable where wrappers cannot expose it. Do not change idle/poll settings.
- [ ] Test invalid headers, aggregate poll counts, duplicate concurrent RPC IDs,
  unsuccessful native operations, bounded metadata, and zero extra device calls.
  Add an instrumentation test that sends a traced read-only request to the real
  server and verifies IDs and timing header presence.
- [ ] Run `./gradlew :automation-server:testDebugUnitTest`. Expect new recorder
  tests to fail before implementation, then PASS with existing interaction tests.
  On the isolated Android benchmark target run
  `./gradlew :automation-server:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.automationserver.NativeTimingIntegrationTest`.
  Record unavailable device execution as an open gate. Commit as
  `feat(performance): measure Android native automation stages`.

### Task 3: Record native iOS stages with request ownership

**Files:**
- Create: `ios-automation-server/IOSAutomationServerUITests/Helpers/NativeTiming.swift`
- Create: `ios-automation-server/IOSAutomationServerTests/NativeTimingTests.swift`
- Modify: `ios-automation-server/IOSAutomationServerUITests/Server/JsonRpcServer.swift`
- Modify: `ios-automation-server/IOSAutomationServerUITests/Bridge/XCUITestBridge.swift`
- Modify: `ios-automation-server/IOSAutomationServerUITests/Helpers/Helpers.swift`
- Modify: `ios-automation-server/IOSAutomationServer.xcodeproj/project.pbxproj`

**Review:** checkpoint. Timeout and main-queue ownership affect native compatibility.

- [ ] Define a Foundation-only recorder available to the unit and UI-test targets.
  Mirror the v1 wire schema, not the JVM implementation. Test with an injected clock.

```swift
func testNativeDurationUsesInjectedMonotonicClock() throws {
    var now: UInt64 = 100
    let timing = NativeTiming(
        invocationId: String(repeating: "a", count: 32),
        requestSpanId: String(repeating: "b", count: 32),
        nowNs: { now }
    )
    timing.measure("operation") { now = 140 }
    let span = try XCTUnwrap(timing.snapshot().spans.first { $0.stage == "operation" })
    XCTAssertEqual(span.durationNs, 40)
}
```

- [ ] Define `NativeTiming` initializer as used above, generic
  `measure<T>(_ stage: String, _ block: () throws -> T) rethrows -> T`,
  `snapshot() -> NativeTimingSnapshot`, and `encodeHeader() -> String?`.
  Snapshot values are immutable. Lock mutable recording state across the HTTP and
  main threads. Explicitly register helper/test files in the Xcode project.
- [ ] Measure receipt-to-main-thread queue delay around `runOnMainThread`, decoding
  where it actually occurs, bridge work, explicit waits/polls, and JSON encoding.
  Carry the context in the dispatched closure and through bridge helper arguments.
- [ ] Split screenshot capture and encoding only where XCTest exposes the boundary.
  Otherwise use `screenshot_capture_encode` and name unavailable sub-stages.
- [ ] Test a queued request that outlives the existing HTTP wait. Return a frozen
  partial snapshot, mark unfinished spans missing, and ensure later completion
  cannot contaminate another request. Preserve existing action dispatch semantics.
- [ ] Test unsupported/invalid request headers, concurrent request IDs, operation
  failure, aggregate limits, and unchanged untraced response dictionaries.
- [ ] Run `xcodebuild test -project ios-automation-server/IOSAutomationServer.xcodeproj -scheme IOSAutomationServer -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:IOSAutomationServerTests`.
  Expect RED for absent recorder behavior, then PASS. Exercise a traced read-only
  request through a running UI-test server and save only sanitized timing evidence.
  Commit as `feat(performance): measure iOS native automation stages`.

### Task 4: Publish compatibility guidance and verify both clients

**Files:**
- Modify: `docs/agentico/specs/performance-tracing.md`
- Modify: `docs/agentico/specs/ios-bundle-distribution.md`
- Modify: `docs/agentico/specs/android-artifact-distribution.md`
- Modify: `docs/performance.md`
- Modify: `CONTRIBUTING.md`
- Modify: `app/src/main/resources/agent-instructions.md`
- Modify: `docs/decisions/TD-015-isolate-local-performance-measurements-from-operation-results.md`
- Modify: `docs/decisions/README.md`

**Review:** checkpoint. Final native delivery review.

- [ ] Document old APK/bundle host-only operation, explicit missing timing data,
  how to rebuild/update for native timing, and no replay after missing metadata.
  Do not require an upgrade for existing commands or add unsupported-method errors.
- [ ] Run `./gradlew :app:test`, `./gradlew :app:e2eTest`, `./gradlew build`, the
  Swift gate from Task 3, and `git diff --check`. Keep Android instrumentation
  evidence from Task 2 and native smoke evidence distinct from unit coverage.
- [ ] Review enabled/disabled responses and error paths against the original
  contracts. Mark TD-015 accepted once protocol and compatibility are implemented
  and verified; update the decision index in the same change.
- [ ] Commit as `docs(performance): document native timing compatibility`, run
  branch review, and record remaining real benchmark and agent evaluation gates.
