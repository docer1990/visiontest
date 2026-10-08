# Host Performance Tracing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development or agentico:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver #57A, optional local host tracing that preserves existing CLI/MCP behavior.

**Architecture:** A process-owned writer receives typed events from coroutine-local
invocation contexts. Shared operation and transport boundaries record measurements
without placing trace data in results. See [TD-015](../../decisions/TD-015-isolate-local-performance-measurements-from-operation-results.md)
and the [design](../specs/2026-10-05-performance-baseline-design.md).

**Tech Stack:** Kotlin/JVM, existing coroutines and Gson, JUnit 5, MockK, MockWebServer.

**Scope:** Host only. Native metadata is 57B. No benchmark speedup claim or #57 closure.

---

## File responsibilities

All Kotlin paths below are relative to `app/src/main/kotlin/com/example/visiontest/`
or `app/src/test/kotlin/com/example/visiontest/` as explicitly listed in each task.
`TraceEvent` owns serialization vocabulary, `TraceRecorder` owns scopes and clocks,
`JsonlTraceSink` owns persistence, and `TraceRuntime` owns process lifecycle.
Production constructors retain source-compatible defaults with disabled tracing.
Internal APIs listed below are implementation contracts, not new public MCP tools.

### Task 1: Define typed events and coroutine correlation

**Files:**
- Create: `app/src/main/kotlin/com/example/visiontest/performance/TraceEvent.kt`
- Create: `app/src/main/kotlin/com/example/visiontest/performance/TraceRecorder.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/performance/TraceRecorderTest.kt`
- Create: `docs/agentico/specs/performance-tracing.md`

**Review:** checkpoint. Every later task consumes the event and context contract.

- [x] Write deterministic tests before implementation. This test establishes the
  duration API; include imports from `kotlin.test` and `kotlinx.coroutines.test`.

```kotlin
@Test
fun `nested spans share invocation and retain monotonic durations`() = runTest {
    var now = 10L
    val events = mutableListOf<TraceEvent>()
    val trace = TraceRecorder(nowNs = { now }, emit = { events.add(it); Unit })
    trace.invocation("get_device_info", "android") {
        trace.span(TraceStage.HTTP_EXCHANGE) { now = 25L }
    }
    val child = events.single { it.stage == TraceStage.HTTP_EXCHANGE }
    val parent = events.single { it.stage == TraceStage.INVOCATION }
    assertEquals(15L, child.durationNs)
    assertEquals(parent.spanId, child.parentSpanId)
    assertEquals(parent.invocationId, child.invocationId)
    assertEquals(TraceOutcome.RETURNED, parent.outcome)
}
```

- [x] Add tests with two overlapping `async` invocations, `withContext(Dispatchers.IO)`,
  nested errors, cancellation, disabled tracing, and unknown operation names.
  Assert distinct invocation IDs and correct parent IDs after suspension. Use
  deterministic clocks, not real-time latency assertions.
- [x] Define these internal interfaces and the complete typed schema from the design.

```kotlin
internal class TraceRecorder(
    private val nowNs: () -> Long = System::nanoTime,
    private val emit: (TraceEvent) -> Unit,
) {
    suspend fun <T> invocation(operation: String, platform: String?, block: suspend () -> T): T
    suspend fun <T> span(stage: TraceStage, block: suspend () -> T): T
    suspend fun metric(name: TraceMetric, value: Long)
    suspend fun operationOutcome(value: OperationOutcome)
}
```

`TraceEvent` has the design's span fields. `TraceStage` includes `INVOCATION`,
`CLI_PREPARE`, `COMPONENT_INIT`, `OPERATION`, `HEALTH`, `REQUEST_PREPARE`,
`HTTP_EXCHANGE`, `RESPONSE_PROCESS`, `POLL`, `POLL_WAIT`, `SCREENSHOT_PARSE`,
`SCREENSHOT_DECODE`, `SCREENSHOT_WRITE`, `DEVICE_DISCOVERY`, `ADB`, `SIMCTL`, and
`PROCESS_LAUNCH`. `TraceMetric` permits only the five metrics in the design.
`TraceOutcome` and `OperationOutcome` use the design's vocabularies.

- [x] Store span context in a `CoroutineContext.Element`, restore parent scopes
  on exit, and emit failure records without replacing the original exception.
  Metric accumulation belongs to the active span and is concurrency-safe.
  Unknown operations serialize as `other`; no arbitrary tags or error strings.
- [x] Specify outcomes, completeness, byte boundaries, and clock domains in the
  behavioral contract. Initially mark native timings unavailable.
- [x] Run `./gradlew :app:test --tests '*TraceRecorderTest'`. Expect RED for missing
  types first, then all cases passing. Commit only this task's files with message
  `feat(performance): add correlated host trace events`.

### Task 2: Persist traces with bounded failure isolation

**Files:**
- Create: `app/src/main/kotlin/com/example/visiontest/performance/JsonlTraceSink.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/performance/JsonlTraceSinkTest.kt`
- Modify: `docs/agentico/specs/performance-tracing.md`

**Review:** checkpoint. Entry-point lifecycle depends on bounded writes and close.

- [x] Define and test the persistence seam using a fake failing writer. The test
  must not depend on filling a disk or timing a slow filesystem.

```kotlin
@Test
fun `writer failure is reported without escaping to the operation`() {
    val warnings = mutableListOf<String>()
    val sink = JsonlTraceSink(
        writer = object : java.io.Writer() {
            override fun write(buffer: CharArray, offset: Int, count: Int) {
                throw java.io.IOException("secret-path")
            }
            override fun flush() = Unit
            override fun close() = Unit
        },
        diagnostic = warnings::add,
    )
    sink.offer("{\"type\":\"session\",\"version\":1}")
    val result = sink.finish(1_000)
    assertFalse(result.complete)
    assertEquals(1, warnings.size)
    assertFalse(warnings.single().contains("secret-path"))
}
```

- [x] Implement `JsonlTraceSink(writer: Writer, diagnostic: (String) -> Unit,
  capacity: Int = 4096)`, `offer(line: String): Boolean`, and
  `finish(timeoutMs: Long): TraceCloseResult`. Define `TraceCloseResult` with
  `complete: Boolean`, `written: Long`, and `dropped: Long`. Make close idempotent.
- [x] Add a path-opening factory that enforces the regular-file, append, symlink,
  and nonblocking exclusive-lock policy. Hold the lock for the writer lifetime.
  Test lock conflict, directory target, missing parent, special-file rejection,
  queue saturation, incomplete shutdown, and independent processes/paths.
- [x] Use one daemon writer thread and reject new events after close. Drop on full
  queue, increment the dropped count, and emit a fixed diagnostic once. Reserve
  orderly finalization for the terminal session record. Never await writes on an
  operation coroutine. An incomplete file remains inspectable, not certified complete.
  Check the deadline before admitting the terminal record. Production file channels
  must close on interruption. For an injected noninterruptible writer, guarantee
  bounded caller return and suppression of new writes after expiry; an already
  admitted write cannot be retracted. Record this limit in the contract and tests.
- [x] Test that serialized events never contain synthetic input text, UI labels,
  paths, or exception messages supplied at nearby instrumented boundaries. Use
  the event serializer rather than accepting arbitrary production event maps.
- [x] Run `./gradlew :app:test --tests '*JsonlTraceSinkTest' --tests '*TraceRecorderTest'`.
  Expect RED for the failing writer behavior before implementation, then PASS.
  Commit as `feat(performance): persist bounded local trace files`.

### Task 3: Activate tracing at CLI and MCP entry points

**Files:**
- Create: `app/src/main/kotlin/com/example/visiontest/performance/TraceRuntime.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/Main.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/VisionTestCli.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/CliErrorHandler.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/ComponentHolder.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/PlatformOption.kt`
- Modify: CLI command adapters under `app/src/main/kotlin/com/example/visiontest/cli/commands/` that call `runCliCommand` directly
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/ToolDsl.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/ToolFactory.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/performance/TraceActivationTest.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/cli/PerformanceTraceE2ETest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt`
- Modify: `docs/agentico/specs/cli.md`

**Review:** checkpoint. All instrumented work needs a correctly owned scope.

- [x] Implement pure configuration resolution behind
  `resolveTracePath(mode: String, cliPath: String?, environmentPath: String?): Path?`.
  Modes are `cli` and `mcp`. Test precedence and validation before device access.

```kotlin
@Test
fun `CLI ignores MCP environment and rejects blank explicit path`() {
    assertNull(resolveTracePath("cli", null, "mcp.jsonl"))
    assertFailsWith<IllegalArgumentException> {
        resolveTracePath("cli", " ", "mcp.jsonl")
    }
    assertEquals(
        java.nio.file.Path.of("trace.jsonl").toAbsolutePath().normalize(),
        resolveTracePath("cli", "trace.jsonl", null),
    )
}
```

- [x] Add the root flag before the subcommand. Preserve `route()` and the ignored
  tail of `serve`. Avoid manually removing option-like strings from command
  arguments; typed text can legitimately contain `--trace-performance`.
- [x] Let `TraceRuntime` own configuration, entry time, writer, and shutdown. Use
  Clikt lifecycle/runner hooks to close `cli.prepare` at validated dispatch,
  then measure lazy component construction. Do not wrap the entire blocking
  `parse()` call and label device execution as parsing.
- [x] Inject traced command runners without changing `CliCommandRunner`'s existing
  signature. Use registered command names and parsed platform values; do not scan
  untrusted raw arguments for trace labels. Preserve test constructors and defaults.
- [x] Make every CLI exit gateway finish tracing before `exitProcess`. Include
  mapped failures and informational exits. MCP gets one invocation per tool call,
  with coroutine context propagated through timeout handling in `ToolScope`.
- [x] Extend actual JAR tests with tracing on/off. Assert identical stdout, exit
  codes, and ordinary error text except the explicitly specified trace diagnostic.
  Check an invalid wait still creates no device components, a trace path failure
  does not change a normal result, MCP output remains JSON-RPC, and tool names
  equal the existing `EXPECTED_TOOLS` set.
- [x] Run `./gradlew :app:test --tests '*TraceActivationTest' --tests '*MainDispatchTest' --tests '*VisionTestCliTest' --tests '*WaitValidationCliTest' --tests '*CliErrorHandlerTest' --tests '*ToolDslTest'`,
  then `./gradlew :app:e2eTest --tests '*PerformanceTraceE2ETest' --tests '*McpStdioE2ETest'`.
  Expect new flag tests to fail before wiring and all checks to pass afterward.
  Commit as `feat(performance): expose CLI and MCP tracing lifecycle`.

### Task 4: Instrument shared HTTP transport and polling

**Files:**
- Modify: `app/src/main/kotlin/com/example/visiontest/common/JsonRpcHttpClient.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/android/AutomationClient.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/ios/IOSAutomationClient.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/ComponentHolder.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/ToolFactory.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/Main.kt`
- Modify: `docs/agentico/specs/performance-tracing.md`
- Create: `app/src/test/kotlin/com/example/visiontest/performance/HttpTraceTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/android/AutomationClientWaitTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/ios/IOSAutomationClientTest.kt`

**Review:** checkpoint. Native correlation and benchmark stage attribution depend on this.

- [x] Add an optional `TraceRecorder? = null` constructor dependency to the common
  transport and both platform clients. Keep existing constructors and member
  methods source-compatible. Wire the recorder through the factories from Task 3.
- [x] Start with this test, importing existing MockWebServer and coroutine helpers.

```kotlin
@Test
fun `tracing preserves body and request count`() = runTest {
    val events = mutableListOf<TraceEvent>()
    val trace = TraceRecorder(emit = { events.add(it); Unit })
    val server = okhttp3.mockwebserver.MockWebServer()
    server.start()
    try {
        val body = """{"jsonrpc":"2.0","id":1,"result":{"success":false}}"""
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(body))
        val client = com.example.visiontest.android.AutomationClient(
            host = server.hostName, port = server.port, trace = trace,
        )
        val response = trace.invocation("android_press_home", "android") {
            client.sendRequest("device.pressHome")
        }
        assertEquals(body, response)
        assertEquals(1, server.requestCount)
        assertEquals(1, events.count { it.stage == TraceStage.HTTP_EXCHANGE })
    } finally {
        server.shutdown()
    }
}
```

- [x] Record preparation, exchange, response processing, and health spans with UTF-8
  byte counts. Keep the HTTP body, JSON-RPC IDs, request count, error mapping, and
  timeouts unchanged. Body bytes are not wire bytes. Never reserialize a result
  to return it merely because tracing is enabled.
- [x] Record polling attempts and cumulative explicit waits. Instrument both
  appearance and disappearance and preserve invalid-response failures. Record
  structured success/failure only where reliable; leave unknown otherwise.
- [x] Test UTF-8 non-ASCII data, HTTP failure, timeout, cancellation, malformed
  responses, normally returned `success:false`, backend failure during gone-wait,
  and distinct concurrent invocations whose existing RPC IDs may both equal 1.
- [x] Run `./gradlew :app:test --tests '*HttpTraceTest' --tests '*AutomationClientTest' --tests '*AutomationClientWaitTest' --tests '*IOSAutomationClientTest' --tests '*IOSAutomationClientPublicApiTest'`.
  Expect missing trace spans at RED and preserved original responses at GREEN.
  Commit as `feat(performance): trace native transport and client waits`.

### Task 5A: Instrument shared operations and screenshots

**Files:**
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrar.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/AndroidDeviceToolRegistrar.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/IOSDeviceToolRegistrar.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/AndroidWaitToolRegistrar.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/IOSWaitToolRegistrar.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/AndroidStopToolRegistrar.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/ScreenshotSaver.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/ComponentHolder.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/ToolFactory.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/performance/OperationTraceTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/tools/AndroidScreenshotToolTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/tools/IOSScreenshotToolTest.kt`

**Review:** checkpoint. Device instrumentation builds on these shared operation scopes.

Task 5 is split into two sequential implementation checkpoints without changing
the approved scope. Task 5A establishes operations and screenshots; Task 5B adds
device and process boundaries.

- [x] Add an optional recorder to `ScreenshotSaver` and the other dependencies,
  passed from the production factories. Write the failure test first.

```kotlin
@Test
fun `invalid screenshot retains returned failure and records decoding failure`() = runTest {
    val events = mutableListOf<TraceEvent>()
    val trace = TraceRecorder(emit = { events.add(it); Unit })
    val saver = com.example.visiontest.tools.ScreenshotSaver(
        "Android", "android_screenshot", "APK", trace = trace,
    )
    val result = trace.invocation("android_screenshot", "android") {
        saver.writeScreenshot(java.io.File("unused.png"), "!")
    }
    assertTrue(result.startsWith("Screenshot failed:"))
    assertEquals(
        OperationOutcome.FAILURE,
        events.single { it.stage == TraceStage.SCREENSHOT_DECODE }.operationOutcome,
    )
    assertFalse(java.io.File("unused.png").exists())
}
```

- [x] Wrap shared registrar operations once, so CLI and MCP use the same operation
  span. Preserve CLI's existing additional health checks for measurement in #63.
  Add no new preflight checks or RPC calls for tracing.
- [x] Record screenshot parsing, decoding, writing, and byte counts, preserving
  atomic replacement and cleanup behavior. Classify handled failures at their
  existing branches, never by searching the returned English string.
- [x] Add screenshot success and I/O failure cases and verify shared operation
  spans across CLI/MCP consumers. Check no trace contains synthetic secret input
  or output. Compare results and interaction counts with tracing disabled.
- [x] Run `./gradlew :app:test --tests '*OperationTraceTest' --tests '*ScreenshotToolTest' --tests '*ToolRegistrarTest'`.
  Expect absent stages at RED, then PASS with unchanged operation expectations.
  Commit as `feat(performance): trace shared operations and screenshots`.

### Task 5B: Instrument device and process work

**Files:**
- Modify: `app/src/main/kotlin/com/example/visiontest/android/Android.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/ios/IOSSimulator.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/ios/ProcessExecutor.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/ios/IOSManager.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrar.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/ComponentHolder.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/ToolFactory.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/Main.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/performance/OperationTraceTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/ios/ProcessExecutorTest.kt`
- Modify: `docs/agentico/specs/performance-tracing.md`

**Review:** checkpoint. This completes the host measurement boundaries before documentation.

- [ ] Start with a process success and privacy regression. Import the existing
  coroutine test and Kotlin assertion helpers. The internal traced constructor
  accepts `trace: TraceRecorder` while existing public constructors remain unchanged.

```kotlin
@Test
fun `process trace preserves output without recording it`() = runTest {
    val events = mutableListOf<TraceEvent>()
    val trace = TraceRecorder(emit = { events.add(it); Unit })
    val executor = com.example.visiontest.ios.ProcessExecutor(trace = trace)
    val result = trace.invocation("ios_get_device_info", "ios") {
        executor.execute("sh", "-c", "printf synthetic-secret")
    }
    assertEquals(0, result.exitCode)
    assertEquals("synthetic-secret", result.output)
    assertEquals(1, events.count { it.stage == TraceStage.SIMCTL })
    assertFalse(events.any { it.toJson().toString().contains("synthetic-secret") })
}
```

- [ ] Pass the optional recorder through production device factories and retain
  existing public Kotlin and JVM constructors, including no-argument constructors.
- [ ] Instrument Android device discovery/library calls and `executeAdb`, plus
  iOS discovery and `ProcessExecutor.execute`. Record fixed stages `adb`, `simctl`,
  and `process.launch`; never executable arguments, paths, or output text.
  Carry context into helper threads explicitly where measurements occur there.
- [ ] Measure detached server launch only around process creation, not daemon
  lifetime, subsequent delays, or health polling. Preserve all existing requests,
  timeout budgets, validation, cleanup, and result/exception behavior.
- [ ] Add subprocess success, nonzero exit, timeout, discovery cache-hit, and
  detached-launch tests. Check no trace contains synthetic secret arguments or
  output. Compare results and interaction counts with tracing disabled.
- [ ] Run `./gradlew :app:test --tests '*OperationTraceTest' --tests '*ProcessExecutorTest' --tests '*AndroidValidationTest' --tests '*IOSSimulatorTest' --tests '*ToolRegistrarTest'`,
  plus recorder tests if timeout classification changes, detekt, and whitespace checks.
  Observe missing stages at RED and preserved behavior at GREEN.
  Commit as `feat(performance): trace device discovery and processes`.

### Task 6: Document, verify, and review host tracing

**Files:**
- Modify: `README.md`
- Modify: `CONTRIBUTING.md`
- Modify: `app/src/main/resources/agent-instructions.md`
- Modify: `docs/agentico/specs/performance-tracing.md`
- Modify: `docs/agentico/specs/cli.md`
- Create: `docs/performance.md`

**Review:** checkpoint. This is the final host delivery review before 57B/57C.

- [ ] Document exact activation syntax, output isolation, file ownership, dropped
  events, bounded draining, CLI preparation boundaries, and missing native data.
  Distributed agent instructions remain self-contained and recommend explicit
  tracing only for measurement, without changing ordinary command guidance.
- [ ] Run `./gradlew :app:test`, `./gradlew :app:e2eTest`, `./gradlew build`, and
  `git diff --check`. Expect all tests and existing coverage/lint gates to pass.
  Do not add timing thresholds or weaken coverage to accommodate new branches.
- [ ] Review requirements against the design and inspect disabled-path behavior.
  Record actual check results and remaining native/benchmark gaps. Commit the
  documentation as `docs(performance): document local host tracing`.
- [ ] Run branch review and resolve findings before the implementation PR. Mark
  57A complete only; #57 remains open for 57B, 57C, and 57D.
