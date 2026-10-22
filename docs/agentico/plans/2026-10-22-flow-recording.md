# Flow Screen Recording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development (recommended) or agentico:executing-plans to implement this plan task-by-task.

**Goal:** Add required, opt-in screen recording to Android and iOS Simulator CLI flows.

**Architecture:** The flow executor owns a recording session around shared prepared operations and observations. Platform adapters use bounded host processes, pin their target and finalize host artifacts after execution ends. Android readiness confirms container initialization; iOS readiness confirms its documented first-frame signal.

**Tech Stack:** Kotlin/JVM 17, kotlinx-coroutines, JUnit 5 dynamic BDD scenarios, MockWebServer, MockK, ADB and simctl. Add no dependencies.

**Branch:** feat/flow-recording off main

**Git workflow:** Agentico default for every step.

**Decisions:** TD-003, TD-005, TD-016, TD-018, TD-019

**Spec:** `docs/agentico/specs/2026-10-22-flow-recording-design.md`

---

## File structure and dependencies

All Kotlin application paths below start at
`app/src/main/kotlin/com/example/visiontest/`. Test Kotlin paths start at
`app/src/test/kotlin/com/example/visiontest/`. Task file lists spell out complete paths.

| Files | Responsibility | Produced by | Consumed by |
| --- | --- | --- | --- |
| `recording/RecordingProcessRunner.kt`, `recording/OwnedRecordingProcess.kt` | Captured commands, process ownership, readiness notifications and bounded termination | Task 1 | Tasks 3, 4, 11 |
| `common/RequestDeadline.kt` | Optional monotonic-clock injection without changing dispatch evidence | Task 1 | Tasks 2–10 |
| `recording/RecordingContainerInspector.kt`, `recording/RecordingDataSource.kt` | Bounded ISO BMFF startup and final sample validation | Task 2 | Tasks 3, 4 |
| `recording/RecordingSession.kt` | Platform-neutral lifecycle and terminal result | Task 3 | Tasks 4–10 |
| `recording/IosRecordingSession.kt`, `ios/IOSDeviceSelection.kt` | Captured simulator discovery, first-frame signal and local MOV finalization | Task 3 | Tasks 5, 11 |
| `recording/AndroidRecordingSession.kt`, `recording/AndroidRecordingCapabilities.kt`, `android/AndroidDeviceSelection.kt` | Captured ADB discovery, duration policy, remote ownership, startup MP4 inspection and transfer | Task 4 | Tasks 5, 11 |
| `cli/FlowRecording.kt`, `cli/FlowReport.kt` | Optional recording configuration, factory and final report | Task 5 | Tasks 6–11 |
| `cli/FlowExecutor.kt`, `cli/FlowObservationCollector.kt`, `cli/commands/FlowCommand.kt` | Recording orchestration, report-commit ordering, shared artifact directory and flag | Tasks 5–10 | Task 11 |
| `cli/FlowFeatureScenarios.kt`, `cli/FlowRecordingBehaviorTest.kt`, `cli/FlowRecordingScenarios.kt` in tests | Existing JUnit feature loader extended with a resource parameter and scenario-local bindings | Task 5 | Tasks 6–10 |
| `recording/RecordingTestFixtures.kt`, `cli/FakeRecordingSession.kt` in tests | Explicit container/process/session fixtures with no real device access | Tasks 1, 2, 5 | Later task tests |
| `cli/FlowRecordingE2ETest.kt`, `cli/RecordingExecutableFixture.kt` in tests | Packaged CLI with private fake executables and HTTP backend | Task 11 | Final verification |
| `benchmarks/recording_probe.py`, `benchmarks/tests/test_recording_probe.py`, `benchmarks/results/flow-recording/` | Paired real-flow measurements, raw samples and provenance | Task 12 | Final review |

Tasks run in order. There are no parallel groups. Each task defines a dependency
or public contract, so every task is a review checkpoint. Do not start a dependent
task before its checkpoint review. The executor records the baseline commit before
implementation and follows the worktree and baseline workflow required by its skill.

## BDD feature files

The six scenarios are in `app/src/test/resources/features/flow-recording.feature`.
Each scenario runs independently for Android and iOS. The existing JUnit feature
loader accepts plain scenarios, so keep this feature in that supported grammar.
Use parameterized unit tests for the larger fault/input matrices.

The feature file is committed before defining its implementation tasks. Tasks
5–10 each implement exactly one scenario using agentico:behavior-driven-development.
Task 10 turns on complete scenario-registration checking. Existing
`features/flow.feature` and its complete registration remain unchanged.

## Execution rules

Read `AGENTS.md`, `kotlin-mcp-server.instruction.md`, the spec and the listed TDs.
Use agentico:test-driven-development for Tasks 1–4 and focused helper tests.
Use agentico:behavior-driven-development for Tasks 5–10. Add scenario bindings
before their flow implementation. Compile-only interface shells may precede a
RED run; a compiler error is not the intended failing assertion. Do not implement
later scenarios early. All earlier registered scenarios must remain green.

The snippets below are test bodies or exact interface declarations. Add their
normal package, imports and JUnit annotations in the named files. Use a fresh
temporary directory and fresh gates for every test. No unit test may discover,
record or signal a real device. Capture subprocess diagnostics rather than
matching arbitrary `ERROR` substrings.

Each task's command runs only its named tests. Expect exit 0 and zero failed
tests at GREEN. A checkpoint reviewer runs the full required JVM gates, not each
implementer. Never change coverage floors, detekt baselines or lint baselines.

### Task 1: Own bounded recording subprocesses

**Files:**
- Create: `app/src/main/kotlin/com/example/visiontest/recording/RecordingProcessRunner.kt`
- Create: `app/src/main/kotlin/com/example/visiontest/recording/OwnedRecordingProcess.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/common/RequestDeadline.kt:20-38`
- Create: `app/src/test/kotlin/com/example/visiontest/recording/RecordingProcessRunnerTest.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/recording/RecordingTestFixtures.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/common/RequestDeadlineTest.kt`

**Review:** checkpoint. Both platform adapters depend on ownership and deadlines.

- [ ] Write failing process lifecycle and injectable-clock tests.

Use this exact clock assertion, with `@OptIn(ExperimentalCoroutinesApi::class)`.

```kotlin
@Test
fun `remaining budget uses the supplied monotonic clock`() = runTest {
    val deadline = RequestDeadline(1000) { testScheduler.currentTime * 1_000_000 }
    advanceTimeBy(250)
    assertEquals(750, deadline.remainingMillis())
    advanceTimeBy(750)
    assertFailsWith<TimeoutException> { deadline.remainingMillis() }
}
```

Use a mocked Java `Process` only at the OS boundary. Add a fixture function
`fun completedProcess(stdout: String, stderr: String, exitCode: Int): Process`
that exposes byte-array streams, an already completed `onExit()` future, the
requested exit value, `isAlive = false`, and a harmless synthetic PID. Exercise
the real runner and verify its returned data rather than mock invocation counts.

```kotlin
@Test
fun `command output stays captured`() = runTest {
    val process = completedProcess("machine-output\n", "diagnostic\n", 0)
    val runner = JvmRecordingProcessRunner { process }
    val result = runner.run(listOf("fixture-command"), RequestDeadline(1000))
    assertEquals("machine-output\n", result.stdout.decodeToString())
    assertEquals("diagnostic\n", result.stderr)
    assertEquals(0, result.exitCode)
}
```

Add gated-process cases for readiness emitted before subscription, output on
both pipes, EOF before readiness, startup exceptions, cancellation before and
after handle registration, timeout, and readers that do not finish immediately.
Verify that capture overflow fails a machine-readable command rather than
silently truncating it. Test an owned real Unix child for SIGINT and reaping,
with a bounded watchdog and a platform assumption on hosts without Unix signals.

- [ ] Implement the process API and clock seam.

```kotlin
internal data class RecordingCommandResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
)

internal class RecordingException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal interface RecordingProcessRunner {
    suspend fun start(
        scope: CoroutineScope,
        command: List<String>,
        deadline: RequestDeadline,
        stderrReadyLine: String? = null,
    ): OwnedRecordingProcess

    suspend fun run(
        command: List<String>,
        deadline: RequestDeadline,
        stdoutLimitBytes: Int = 1_048_576,
    ): RecordingCommandResult
}

internal interface OwnedRecordingProcess {
    val pid: Long
    val firstStdoutLine: Deferred<String>
    val stderrReady: Deferred<Unit>
    val exit: Deferred<RecordingCommandResult>
    fun isAlive(): Boolean
    suspend fun interrupt(deadline: RequestDeadline)
    suspend fun forceStop(deadline: RequestDeadline)
}
```

Implement `JvmRecordingProcessRunner` with constructor parameter
`launch: (List<String>) -> Process = { ProcessBuilder(it).start() }`. Register
ownership before any cancellable handoff. Use child tasks for stream draining
and process exit; do not let a monitor exception cancel its owner's coordinator.
Retain at most 65536 diagnostic bytes and reject an overlong readiness line.
Short commands require complete stdout up to their stated cap. Long-running
process stdout is a bounded protocol/diagnostic capture, never a video buffer.
Readiness must inspect output while it streams, not after exit.

Cancellation and timeout stop and reap owned short-command processes. Long-lived
handles remain owned by their recording session until explicit finalization.
On Unix, send SIGINT to the owned live process rather than treating
`Process.destroy()` as an interrupt. Make termination idempotent, bound reader
shutdown, and do not kill shared services such as the ADB daemon.

Add the optional `nowNs: () -> Long = System::nanoTime` constructor argument to
`RequestDeadline`. Replace its two direct clock reads. Keep its context key,
HTTP API and `actionDispatched` behavior unchanged.

- [ ] Verify and commit.

Run `./gradlew --console=plain :app:test --tests '*RecordingProcessRunnerTest' --tests '*RequestDeadlineTest'`.
Commit the listed files with `feat: add owned recording process lifecycle`.

### Task 2: Inspect recording containers without loading videos

**Files:**
- Create: `app/src/main/kotlin/com/example/visiontest/recording/RecordingDataSource.kt`
- Create: `app/src/main/kotlin/com/example/visiontest/recording/RecordingContainerInspector.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/recording/RecordingContainerInspectorTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/recording/RecordingTestFixtures.kt`

**Review:** checkpoint. Readiness and artifact publication depend on this parser.

- [ ] Write structural tests using independently constructed byte fixtures.

Add test helpers `initializedMovie(): ByteArray`,
`movieWithMetadataBeforeVideo(): ByteArray`, `movieWithInvalidVideoOffset(): ByteArray`
and `ByteArrayRecordingSource(bytes: ByteArray) : RecordingDataSource`.
Build explicit box layouts in the helpers; do not use production serialization
or production validation to construct expected results.

```kotlin
@Test
fun `initialization does not require flushed video samples`() = runTest {
    val inspector = RecordingContainerInspector()
    val source = ByteArrayRecordingSource(initializedMovie())
    assertTrue(inspector.initialized(source, RequestDeadline(1000)))
}

@Test
fun `metadata before video does not invalidate a recording`() = runTest {
    val path = directory.resolve("recording.mp4")
    Files.write(path, movieWithMetadataBeforeVideo())
    RecordingContainerInspector().validateVideo(path, RequestDeadline(1000))
}

@Test
fun `video offsets outside media data are rejected`() = runTest {
    val path = directory.resolve("invalid.mp4")
    Files.write(path, movieWithInvalidVideoOffset())
    assertFailsWith<RecordingException> {
        RecordingContainerInspector().validateVideo(path, RequestDeadline(1000))
    }
}
```

Cover ordinary/extended headers, open-ended in-progress `mdat`, incomplete
headers, arbitrary `mdat` text, empty files, metadata-only tracks, zero video
samples, constant and per-sample sizes, `stco`/`co64`, multiple chunks, corrupt
table counts, overflow, truncated boxes and expired deadlines. Inspect a valid
video track even when another track or its samples appear first.

- [ ] Implement bounded random-access inspection.

```kotlin
internal fun interface RecordingDataSource {
    suspend fun read(offset: Long, maxBytes: Int, deadline: RequestDeadline): ByteArray
}
```

`RecordingContainerInspector` has a no-argument constructor and these suspend
methods: `initialized(source: RecordingDataSource, deadline: RequestDeadline): Boolean`
and `validateVideo(path: Path, deadline: RequestDeadline): Unit`.

Parse standard unfragmented MP4/MOV output from the selected native tools. Skip
unknown well-formed boxes by their declared size. Do not scan for marker text or
assume media payload starts with a video sample. For final validation, resolve a
`vide` track's sample sizes, chunk offsets and sample-to-chunk table into actual
`mdat` ranges. A metadata track must not satisfy video validation. Reject
fragmented or unsupported layouts explicitly rather than accepting unchecked data.

Use bounded file/channel reads and safe arithmetic. Bound box nesting and table
work, test those limits, and check the supplied deadline during traversal. Do not
read the complete media file into a byte array or decode every frame. A partial
startup file returns false; malformed or unsupported structures return a specific
`RecordingException`. A completed invalid video always fails validation.

- [ ] Verify and commit.

Run `./gradlew --console=plain :app:test --tests '*RecordingContainerInspectorTest'`.
Commit the listed files with `feat: validate recording container readiness and samples`.

### Task 3: Record an iOS simulator through an owned session

**Files:**
- Create: `app/src/main/kotlin/com/example/visiontest/recording/RecordingSession.kt`
- Create: `app/src/main/kotlin/com/example/visiontest/recording/IosRecordingSession.kt`
- Create: `app/src/main/kotlin/com/example/visiontest/ios/IOSDeviceSelection.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/ios/IOSSimulator.kt:40-55,217-248`
- Create: `app/src/test/kotlin/com/example/visiontest/recording/IosRecordingSessionTest.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/ios/IOSSimulatorParsingTest.kt`

**Review:** checkpoint. The executor and Android adapter share this session contract.

- [ ] Write session tests around a fake `RecordingProcessRunner` and real container inspection.

Define a scenario-local fake runner in `IosRecordingSessionTest.kt`. It records
argument vectors, returns a complete simulator-list JSON, exposes a readiness
gate and creates an independently constructed valid MOV on intentional stop.

```kotlin
@Test
fun `the session waits for the first frame before returning`() = runTest {
    val runner = FakeIosRecordingProcesses(directory)
    val session = IosRecordingSession(backgroundScope, directory, runner)
    val start = async { session.start(RequestDeadline(1000)) }
    runCurrent()
    assertFalse(start.isCompleted)
    runner.firstFrame.complete(Unit)
    start.await()
    assertEquals("simulator-1", runner.recordedTarget)
    val path = session.finish(RequestDeadline(1000))
    assertEquals(directory.resolve("recording.mov"), path)
    assertTrue(Files.isRegularFile(path))
}
```

The fake's exact test properties are `firstFrame: CompletableDeferred<Unit>` and
`recordedTarget: String?`. Cover exit-before-readiness, readiness then immediate
exit, missing `xcrun`, malformed/oversized discovery output, no simulator, shutdown
selection, blank UDID, invalid final MOV, stop timeout, repeated finalization and
cancellation during discovery or startup. A terminal failure is a value or an
observed exception, never an unhandled sibling cancellation.

- [ ] Implement the shared session contract and iOS adapter.

```kotlin
internal data class RecordingExit(val exitCode: Int?, val diagnostics: String)

internal interface RecordingSession {
    val terminal: Deferred<RecordingExit>
    suspend fun start(deadline: RequestDeadline)
    suspend fun finish(deadline: RequestDeadline): Path
}
```

Give `IosRecordingSession` constructor parameters `scope: CoroutineScope`,
`artifacts: Path`, `processes: RecordingProcessRunner`, and
`inspector: RecordingContainerInspector = RecordingContainerInspector()`.
Construction allocates ownership state only. Discovery and file creation occur
in `start`. Run captured `xcrun simctl list devices available --json`; extract the
existing stateless parser into `parseIosSimulatorDevices(jsonOutput: String): List<MobileDevice>`
and selection into `selectFirstAvailableSimulator(devices: List<MobileDevice>): MobileDevice`.
Keep `IOSSimulator.parseDeviceList` as a delegating member for existing tests.
Preserve ordering and booted-first selection. Do not boot a shutdown selection.

Record the explicit UDID using H.264 into a temporary `.mov` path. Await the
documented stderr line and check liveness. Finalize with SIGINT, await exit,
validate the MOV and publish `recording.mov` by a sibling move. Mark intentional
stop before signalling, cache the finalization outcome, and use one supplied
deadline across every finalization phase. A session held before `start` fails
must still clean up owned resources when `finish` is called. Preserve missing
simulator exit-4 exceptions; wrap tool/parsing/filesystem failures as recording
execution errors, not argument errors.

- [ ] Verify and commit.

Run `./gradlew --console=plain :app:test --tests '*IosRecordingSessionTest' --tests '*IOSSimulatorParsingTest' --tests '*IOSSimulatorTest'`.
Commit the listed files with `feat: record iOS flows with owned simctl sessions`.

### Task 4: Record Android with bounded remote ownership

**Files:**
- Create: `app/src/main/kotlin/com/example/visiontest/recording/AndroidRecordingSession.kt`
- Create: `app/src/main/kotlin/com/example/visiontest/recording/AndroidRecordingCapabilities.kt`
- Create: `app/src/main/kotlin/com/example/visiontest/android/AndroidDeviceSelection.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/recording/AndroidRecordingSessionTest.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/recording/AndroidRecordingCapabilitiesTest.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/android/AndroidDeviceSelectionTest.kt`

**Review:** checkpoint. CLI integration depends on target, duration and cleanup guarantees.

- [ ] Write duration-boundary, target-selection and adapter lifecycle tests.

```kotlin
@Test
fun `a limited recorder rejects a budget one millisecond above its limit`() {
    val capabilities = AndroidRecordingCapabilities.parse(
        "--time-limit TIME\nSet the maximum recording time, in seconds. Maximum / default is 180."
    )
    assertEquals(180L, capabilities.durationSeconds(180_000))
    assertFailsWith<RecordingException> { capabilities.durationSeconds(180_001) }
}

@Test
fun `offline devices cannot become the recording target`() {
    val devices = parseAdbDevices(
        "List of devices attached\nserial-offline\toffline\nserial-1\tdevice\nserial-2\tdevice\n"
    )
    assertEquals("serial-1", devices.first().id)
    assertEquals(listOf("serial-1", "serial-2"), devices.map { it.id })
}
```

Add capability fixtures for `Default is 180. Set to 0 to remove the time limit.`,
unknown help, malformed bounds and overflow. A default without a declared
maximum or unlimited support does not establish a supported upper bound.

Use a fake runner that returns an initialized MP4 without media payload and a
live owned remote PID. Assert that startup completes before frames are flushed.
During finalization, return a valid metadata-first MP4 and assert the published
path. Cover internal codec fallback diagnostics, zero/nonzero early exit, target
disconnect, wrong serial/endpoint, missing executable, failed pull, invalid video,
signal failure and cancellation before the PID notification is consumed.

- [ ] Implement the Android adapter with these interfaces.

`AndroidRecordingSession` implements `RecordingSession` and has constructor
parameters `scope: CoroutineScope`, `artifacts: Path`, `requiredDurationMs: Long`,
`processes: RecordingProcessRunner`, and
`inspector: RecordingContainerInspector = RecordingContainerInspector()`.

`AndroidRecordingCapabilities` exposes companion function
`parse(help: String): AndroidRecordingCapabilities` and member
`durationSeconds(requiredDurationMs: Long): Long`. It returns a finite rounded-up
duration that fits the advertised capability, even when unlimited recording is
available. Reject unknown capability or excessive duration before launching the
recorder. Test default required budgets of 150000 ms without evidence and
155000 ms with default requested evidence.

`parseAdbDevices(output: String): List<MobileDevice>` returns online devices in
wire order, with state `DEVICE` and type `DeviceType.ANDROID`. Validate the header
and line grammar; valid empty output maps to `NoDeviceAvailableException`, while
malformed output maps to `RecordingException`. Do not invoke Adam discovery or
`Android.executeAdb`, whose lifecycle and allowlist do not fit recording.

Resolve ADB from the existing PATH launcher convention once. Honor the existing
`ANDROID_ADB_SERVER_PORT` JVM-property/environment precedence with an explicit
`-P` on every ADB command. Reject contradictory endpoint overrides instead of
discovering on one server and recording on another. Use one captured `adb devices`
call, then pin the selected serial. Every device command must include `-s`.

Run `screenrecord --help` under the startup budget. Create unique remote media
and ownership paths under `/data/local/tmp/`. Launch through an owned remote
wrapper whose PID/ownership protocol is established before readiness. Install
remote interruption cleanup before launching its recorder child. The wrapper
must forward interruption to that child and reap it if the ADB connection closes.
Use only generated paths and validated PID values in the shell wrapper; never
interpolate flow arguments. Verify remote ownership before signalling or deleting.

Acquire the owned PID independently of container readiness. If startup is
cancelled before the notification arrives, recover ownership from the unique
remote ownership record, interrupt the owned process and close/reap the transport.
Test this race with gates. Do not use global `pkill`, an unscoped `pidof`, or treat
host ADB exit as proof that the remote recorder stopped.

Provide startup-only bounded range reads through `RecordingDataSource`; poll at
50 ms intervals within the shared startup deadline. Read initialized box headers,
not a complete video. Stop the remote recorder normally, await finalization, pull
to a host temporary file, validate and publish `recording.mp4`. Remove owned
remote files after successful transfer. On transfer failure, retain the remote
media when possible and include its path and pinned serial in the diagnostic.
Cleanup shares the supplied finalization deadline and leaves no background
startup task running after a normal timeout return.

- [ ] Verify and commit.

Run `./gradlew --console=plain :app:test --tests '*AndroidRecordingSessionTest' --tests '*AndroidRecordingCapabilitiesTest' --tests '*AndroidDeviceSelectionTest'`.
Commit the listed files with `feat: record Android flows with pinned recorder ownership`.

### Task 5: Save the requested flow recording

**Files:**
- Create: `app/src/main/kotlin/com/example/visiontest/cli/FlowRecording.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/FlowReport.kt:22-59`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/FlowExecutor.kt:22-33`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/commands/FlowCommand.kt:22-26,50-96`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/FlowObservationCollector.kt:31-58`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/FlowFeatureScenarios.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingBehaviorTest.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingScenarios.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/cli/FakeRecordingSession.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingCommandTest.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingReportTest.kt`

**Review:** checkpoint. This introduces the CLI and report contract used by later scenarios.

**BDD:** Use agentico:behavior-driven-development with
`app/src/test/resources/features/flow-recording.feature`, scenario
`Save the requested flow recording`.

```gherkin
Scenario: Save the requested flow recording
  Given a valid flow with recording requested
  When the recorder starts and the flow succeeds
  Then the flow succeeds with a saved video
  And the video includes the requested final observation
```

- [ ] Bind the scenario before adding flow recording behavior.

Give `FlowFeatureScenarios.tests` a trailing parameter
`resourcePath: String = "/features/flow.feature"` and pass it to its reader. Keep
the old default and complete-registration behavior. `FlowRecordingBehaviorTest`
creates a dynamic container per platform with fresh bindings per scenario.
Initially register only this scenario, with `requireComplete = false`.

Define `FakeRecordingSession` with `events: MutableList<String>`,
`terminal: CompletableDeferred<RecordingExit>`, `startFailure: Exception?`,
`finishFailure: Exception?`, `beforeFinish: suspend () -> Unit`, and a constructor
`FakeRecordingSession(artifacts: Path)`. `start` records `record-start`, while
`finish` records `record-stop`, runs the gate and publishes an independent valid
container at the platform-selected fixture path. Its default path can be MP4
because executor tests exercise lifecycle, not platform encoding. The fake must
not complete `terminal` until a test causes premature exit or `finish` requests
intentional stop.

Use real prepared-operation closures and `FlowObservationFixture` HTTP responses.
Record events for an action and final screenshot observation. Assert this order
and the returned report, not just that a factory was called.

```kotlin
assertEquals(listOf("record-start", "action", "final-observation", "record-stop"), events)
assertTrue(report.success)
assertEquals(0, report.exitCode)
assertEquals("succeeded", report.recording?.status)
assertTrue(Path.of(assertNotNull(report.recording?.path)).isAbsolute)
assertEquals(setOf("status", "phase", "path", "error"),
    report.toJson().getValue("recording").jsonObject.keys)
```

Add regression tests for both platforms and all three input sources. Invalid
later steps, step-level `--record` and invalid observation options must never
initialize recording dependencies or create artifacts. A flow without the flag
retains the exact old report key set and does not dereference the recorder lazy.
These regression assertions accompany the new scenario, rather than pretending
already-supported disabled behavior is a new failing BDD feature.

- [ ] Implement optional flow wiring and successful reporting.

```kotlin
internal data class FlowRecordingReport(
    val status: String,
    val phase: String? = null,
    val path: String? = null,
    val error: FlowError? = null,
)

internal fun interface FlowRecordingFactory {
    fun create(
        scope: CoroutineScope,
        platform: Platform,
        artifacts: Path,
        requiredDurationMs: Long,
    ): RecordingSession
}

internal data class FlowRecordingOptions(
    val artifacts: Path,
    val factory: Lazy<FlowRecordingFactory>,
)
```

Implement `SystemFlowRecordingFactory` with default process runner and platform
adapters. Its `create` method allocates a session without device or file access.
Add a trailing constructor parameter to `FlowCommand`:
`recordingFactory: Lazy<FlowRecordingFactory> = lazy { SystemFlowRecordingFactory() }`.
Add a trailing `recordingOptions: FlowRecordingOptions? = null` argument to
`FlowExecutor.execute`, and a trailing `recording: FlowRecordingReport? = null`
property to `FlowReport`. Existing callers retain their defaults.

Add `--record` as a flow-level flag. After complete preparation, generate one
absolute invocation artifact directory and pass it to recording and observations.
Do not relocate explicit screenshot steps. With recording disabled, retain the
existing path. Serialize `recording` only when requested, retain schema version 1
and render recording status/path/error in text mode.

Give `FlowExecutor` an optional constructor parameter
`deadlineFactory: (Long) -> RequestDeadline = { RequestDeadline(it) }`.
Use it for overall, step and startup budgets. Add the same optional
factory as a trailing argument to `FlowObservationOptions.finish` so evidence
uses the same injected clock in tests. Retain default production clocks.

Within a structured scope, allocate the session before `start`, bound startup
by `minOf(10000, overall.remainingMillis())`, execute the existing steps and
observations, then finalize the success path within the remaining overall budget,
capped at 30000 ms. Task 10 introduces the independent budget after execution
expiry; its scenario must fail before that change. Compute required
recording duration as configured execution budget plus requested evidence budget
plus 30000 ms; startup is already inside execution and is not counted twice.

- [ ] Verify and commit.

Run `./gradlew --console=plain :app:test --tests '*FlowRecording*Test' --tests '*FlowCommandTest' --tests '*FlowExecutorTest' --tests '*FlowObservationTest' --tests '*FlowBehaviorTest'`.
Commit the listed files with `feat: add required flow recording flag and reports`.

### Task 6: Reject a flow whose recorder cannot start

**Files:**
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/FlowExecutor.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingScenarios.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingBehaviorTest.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingStartupTest.kt`

**Review:** checkpoint. This defines the startup failure and no-action contract.

**BDD:** Use agentico:behavior-driven-development with
`app/src/test/resources/features/flow-recording.feature`, scenario
`Reject a flow whose recorder cannot start`.

```gherkin
Scenario: Reject a flow whose recorder cannot start
  Given a valid flow with recording requested
  When the recorder cannot start
  Then the flow fails with the recording error
  And every flow step is skipped
  And no observations or failure evidence are collected
```

- [ ] Register the scenario and fail on a recorder initialization error.

Set `FakeRecordingSession.startFailure = RecordingException("Encoder initialization failed")`.
Request both a final screenshot and failure screenshot. Use real prepared
closures whose execution would be visible in an event list.

```kotlin
assertFalse(report.success)
assertEquals(1, report.exitCode)
assertEquals("Encoder initialization failed", report.error?.message)
assertTrue(report.steps.all { it.status == "skipped" })
assertTrue(report.observations.all { it.status == "skipped" })
assertTrue(report.evidence.all { it.status == "skipped" })
assertEquals("start", report.recording?.phase)
assertFalse(events.contains("action"))
```

Parameterize focused cases for missing device exit 4, missing tools, unknown
duration capability, excessive duration, unwritable destination, startup timeout
and component/factory creation failures. Test a 5 ms remaining flow budget and
the 10000 ms startup ceiling with a shared virtual clock. A fake factory that
throws must still produce a recording startup report once execution has begun.

- [ ] Implement startup failure reporting and cleanup.

Catch startup errors inside the execution/report boundary. Preserve existing
missing-device mappings; recorder capability/tool/filesystem errors map to 1.
Build skipped records with original stable step IDs. Skip observations/evidence
explicitly instead of invoking `FlowObservationOptions.finish` on this path.
Finalize any allocated session under the separate budget, preserving the startup
error if cleanup also fails. Never run a step after a startup deadline expires.
Parent cancellation remains cancellation, with bounded cleanup rather than a
fabricated ordinary flow failure.

- [ ] Verify and commit.

Run `./gradlew --console=plain :app:test --tests '*FlowRecordingBehaviorTest' --tests '*FlowRecordingStartupTest' --tests '*FlowRecordingCommandTest'`.
Commit the listed files with `fix: fail recorded flows before actions on startup errors`.

### Task 7: Stop a flow when its recorder exits early

**Files:**
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/FlowExecutor.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/FlowObservationCollector.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingScenarios.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingBehaviorTest.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingInterruptionTest.kt`

**Review:** checkpoint. Later error precedence depends on the report-commit boundary.

**BDD:** Use agentico:behavior-driven-development with
`app/src/test/resources/features/flow-recording.feature`, scenario
`Stop a flow when its recorder exits early`.

```gherkin
Scenario: Stop a flow when its recorder exits early
  Given a recorded flow with an action awaiting its response
  When the recorder exits before the flow finishes
  Then the flow fails with the recording error
  And the pending action has an unknown effect
  And later steps are skipped without replay
```

- [ ] Bind the scenario using a real cancellable HTTP request.

Reuse `FlowLostResponseScenario`'s `AutomationClient`/`IOSAutomationClient` direct
`sendRequest("device.pressHome")` closures and MockWebServer. Hold the response
open with a gate. Complete the fake recorder's terminal only after the server
has received the POST. Do not manufacture dispatch by a test-only setter.

```kotlin
assertEquals("uncertain", report.steps[0].status)
assertEquals("unknown", report.steps[0].certainty)
assertEquals("skipped", report.steps[1].status)
assertEquals("record", report.recording?.phase)
assertEquals(1, report.exitCode)
assertEquals(1, server.requestCount)
assertEquals(0, laterCalls)
```

Test recorder exit codes 0 and 7, exit before dispatch, between steps, during
final observations and after response validation. Gate response evaluation and
report commit independently to prove a validated success or failure is retained
if cancellation arrives at a coroutine handoff. Assert no extra recorder polling
requests occur between steps. A valid partial video may have a path while its
recording status remains failed.

- [ ] Coordinate process exit without cancelling the report owner.

Race terminal notification against active step/observation work in a live
coordinator. Cancel only the active operation child. Do not cancel the coordinator
job, because current `ensureActive()` checks would bypass report production.
Keep a validated outcome independently of cancellable result delivery, then
commit it exactly once. If no validated outcome exists, classify action
uncertainty from the existing `RequestDeadline.actionDispatched` marker.

Stop later dispatch after recording failure. An intentional stop is recorded
before signalling, so its exit cannot be misclassified as premature. A terminal
error observed before intentional stop remains a recording error even when the
native exit code is zero. Preserve the existing no-replay and no-rollback rules.

- [ ] Verify and commit.

Run `./gradlew --console=plain :app:test --tests '*FlowRecordingBehaviorTest' --tests '*FlowRecordingInterruptionTest' --tests '*FlowPreparedExecutionTest' --tests '*RequestDeadlineTest'`.
Commit the listed files with `fix: stop recorded flows on premature recorder exit`.

### Task 8: Preserve the original flow failure

**Files:**
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/FlowExecutor.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/FlowObservationCollector.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingScenarios.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingBehaviorTest.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingEvidenceTest.kt`

**Review:** checkpoint. This establishes failure precedence across evidence and finalization.

**BDD:** Use agentico:behavior-driven-development with
`app/src/test/resources/features/flow-recording.feature`, scenario
`Preserve the original flow failure`.

```gherkin
Scenario: Preserve the original flow failure
  Given a recorded flow whose first action has failed
  When the recorder also fails during failure evidence collection
  Then the original action error remains the primary failure
  And the recording error is reported separately
  And failure evidence is collected only once
```

- [ ] Register bindings around the existing observation fixture.

Make the first action fail with `ServerNotRunningException("Action backend lost")`
after recording starts. Gate the requested evidence response and complete the
recorder terminal while that evidence is active. Release evidence normally.

```kotlin
assertEquals(3, report.exitCode)
assertEquals("Action backend lost", report.error?.message)
assertEquals("failed", report.recording?.status)
assertEquals("record", report.recording?.phase)
assertEquals(1, report.evidence.size)
assertEquals("succeeded", report.evidence.single().status)
assertEquals(1, evidenceRequests)
```

Add cases for returned operation failure, final-observation failure, evidence
failure, evidence timeout and recorder-first failure followed by evidence failure.
Assert recording stays active through successful evidence collection when its
process is healthy. Exercise first-committed-error ordering with gates rather
than relying on scheduler luck.

- [ ] Preserve the primary report and finish evidence once.

Carry the first committed primary failure unchanged through later phases.
Recorder errors get their own recording result. If evidence was already active
when the recorder exited, let it finish under its existing deadline. If recording
failed during execution, collect requested evidence once after execution stops.
Do not rerun evidence after video finalization fails. Preserve skipped final
observation records and the existing independent evidence budget.

- [ ] Verify and commit.

Run `./gradlew --console=plain :app:test --tests '*FlowRecordingBehaviorTest' --tests '*FlowRecordingEvidenceTest' --tests '*FlowObservationTest' --tests '*FlowTextOutputTest'`.
Commit the listed files with `fix: preserve flow errors across recording evidence failures`.

### Task 9: Fail a flow whose video cannot be saved

**Files:**
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/FlowExecutor.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/FlowReport.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingScenarios.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingBehaviorTest.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingFinalizationTest.kt`

**Review:** checkpoint. This completes the required-artifact success contract.

**BDD:** Use agentico:behavior-driven-development with
`app/src/test/resources/features/flow-recording.feature`, scenario
`Fail a flow whose video cannot be saved`.

```gherkin
Scenario: Fail a flow whose video cannot be saved
  Given a recorded flow whose steps have succeeded
  When the video cannot be saved correctly
  Then the flow fails with the recording error
  And the completed steps remain successful
  And no invalid video path is reported
```

- [ ] Bind finalization failure after successful steps.

Set `FakeRecordingSession.finishFailure = RecordingException("Video transfer failed")`.

```kotlin
assertFalse(report.success)
assertEquals(1, report.exitCode)
assertTrue(report.steps.all { it.status == "succeeded" })
assertNull(report.failedStep)
assertEquals("finalize", report.recording?.phase)
assertNull(report.recording?.path)
assertEquals("Video transfer failed", report.recording?.error?.message)
assertTrue(report.text().contains("Video transfer failed"))
```

Parameterize transfer, invalid-container, zero-video-sample, rename, signal and
finalization-timeout failures. Assert the structured report contains only one
recording object, no video bytes and no remote path in its host `path` field.
Remote recovery details may appear in the diagnostic message. Check that a
prior error remains primary, and a valid partial artifact never implies a
successful complete recording.

- [ ] Merge finalization outcomes without rewriting step results.

Publish the validated host path only after adapter success. A finalization error
makes an otherwise successful flow fail with exit 1 and phase `finalize`.
Retain the first earlier recording phase/error if recording had already failed.
Keep completed/skipped step statuses unchanged and render both primary and
recording errors when they differ. Apply the existing stderr/stdout framing.

- [ ] Verify and commit.

Run `./gradlew --console=plain :app:test --tests '*FlowRecordingBehaviorTest' --tests '*FlowRecordingFinalizationTest' --tests '*FlowRecordingReportTest' --tests '*FlowTextOutputTest'`.
Commit the listed files with `fix: require a validated saved flow video`.

### Task 10: Save the video after the flow deadline expires

**Files:**
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/FlowExecutor.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/FlowRecording.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/recording/IosRecordingSession.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/recording/AndroidRecordingSession.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingScenarios.kt`
- Modify: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingBehaviorTest.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingCancellationTest.kt`

**Review:** checkpoint. All six scenarios must be executable before packaged/device tests.

**BDD:** Use agentico:behavior-driven-development with
`app/src/test/resources/features/flow-recording.feature`, scenario
`Save the video after the flow deadline expires`.

```gherkin
Scenario: Save the video after the flow deadline expires
  Given a recorded flow with a short execution deadline
  When the execution deadline expires during an action
  Then the flow retains its timeout failure
  And the recorder stops within its separate finalization budget
  And a valid video path is reported
```

- [ ] Bind the timeout scenario with aligned virtual clocks.

Construct `FlowExecutor` with
`{ ms -> RequestDeadline(ms) { testScheduler.currentTime * 1_000_000 } }`.
Have an action exceed a 100 ms flow budget. Let fake finalization take 200 ms.

```kotlin
assertFalse(report.success)
assertEquals(1, report.exitCode)
assertEquals("TimeoutException", report.error?.type)
assertEquals("succeeded", report.recording?.status)
assertNotNull(report.recording?.path)
assertTrue(events.indexOf("record-stop") > events.indexOf("action"))
```

Test cancellation during discovery, readiness, an active action, final observation,
failure evidence and file transfer. Test finalization that never completes and
prove all finalization phases share one 30000 ms budget rather than each receiving
a fresh one. Assert caller cancellation propagates after cleanup, owned processes
are reaped, shutdown registrations are removed and unrelated processes survive.

- [ ] Complete bounded cancellation cleanup and feature registration.

Run finalization inside a cancellation-resistant context with its explicit total
deadline. Preserve external cancellation rather than converting it to an ordinary
recording failure. Register bounded JVM-shutdown cleanup only for an owned active
recording session; unregister it after normal finalization. Coordinate normal
cleanup and shutdown so they cannot signal or publish twice. Abrupt host/process
termination remains outside the saved-video/report guarantee.

Set `requireComplete = true` for the new recording feature. The ordinary
`:app:test` task already discovers JUnit factories, so no Gradle dependency or
new BDD engine is needed. Verify that an unregistered feature scenario now fails
the feature-registration check.

- [ ] Verify and commit.

Run `./gradlew --console=plain :app:test --tests '*FlowRecordingBehaviorTest' --tests '*FlowRecordingCancellationTest' --tests '*IosRecordingSessionTest' --tests '*AndroidRecordingSessionTest' --tests '*FlowExecutorTest'`.
Commit the listed files with `fix: finalize flow recordings after timeout and cancellation`.

### Task 11: Verify packaged recording commands

**Files:**
- Create: `app/src/test/kotlin/com/example/visiontest/cli/FlowRecordingE2ETest.kt`
- Create: `app/src/test/kotlin/com/example/visiontest/cli/RecordingExecutableFixture.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/recording/RecordingProcessRunner.kt` only for process-boundary defects exposed by these tests
- Modify: `app/src/main/kotlin/com/example/visiontest/cli/FlowRecording.kt` only for packaged wiring defects exposed by these tests

**Review:** checkpoint. Task 12 exercises the packaged CLI on real targets.

- [ ] Write packaged CLI regressions first.

Follow `FlowCommandE2ETest`'s shadow-JAR launch and temporary stdout/stderr files.
Use private fake `adb` and `xcrun` executables, with explicit fake PATH/SDK
environment and a loopback MockWebServer. Recording discovery must stay on its
captured host-command seam, so this test never reaches Adam or a real ADB daemon.
Fake recorder processes must expose real readiness, signal handling and exit
behavior; a prewritten JSON report is not an integration test. Generate structural
video fixtures from the independent test builders.

Use the existing E2E port-availability assumption and ensure every child has a
watchdog longer than the specific behavior under test. All input modes and both
platforms must cover valid recording, startup failure and invalid-later-step
preflight. Add text mode, premature exit, invalid final video and owned-process
cleanup coverage. For a startup error, assert the actual packaged process output.

```kotlin
assertEquals(1, result.exitCode)
val lines = result.stdout.trim().lines()
assertEquals(1, lines.size)
val report = Json.parseToJsonElement(lines.single()).jsonObject
assertEquals(false, report.getValue("success").jsonPrimitive.boolean)
assertEquals("start", report.getValue("recording").jsonObject.getValue("phase").jsonPrimitive.content)
assertFalse(report.containsKey("jsonrpc"))
assertTrue(result.stderr.contains("record", ignoreCase = true))
```

`RecordingExecutableFixture` exposes `run(platform: Platform, inputMode: String,
failure: String?, json: Boolean): PackagedRecordingResult`, with
`PackagedRecordingResult(val exitCode: Int, val stdout: String, val stderr: String)`.
Keep its generated scripts and invocation state in one test-owned directory.

- [ ] Implement the isolated executable fixture and address demonstrated boundary defects.

Use real child processes, private files and the test-owned HTTP server. Do not
change the public contract to accommodate a fixture. Production changes in this
task must be driven by a failing integration assertion. If a case already passes,
retain it as regression coverage rather than claiming a new RED/GREEN cycle.

- [ ] Verify and commit.

Run `./gradlew --console=plain :app:e2eTest --tests '*FlowRecordingE2ETest'`.
Commit the listed files with `test: verify packaged flow recording lifecycle`.

### Task 12: Document recording and measure complete flows

**Files:**
- Modify: `docs/cli-flow.md`
- Modify: `docs/cli-json.md`
- Modify: `docs/agentico/specs/cli.md:313-364`
- Modify: `README.md:101-104`
- Modify: `CLAUDE.md` and `CONTRIBUTING.md` where flow behavior is described
- Modify: `app/src/main/resources/agent-instructions.md:100-141`
- Create: `benchmarks/recording_probe.py`
- Create: `benchmarks/tests/test_recording_probe.py`
- Create: `benchmarks/results/flow-recording/README.md`
- Create: `benchmarks/results/flow-recording/samples.jsonl`
- Create: `benchmarks/results/flow-recording/environment.json`

**Review:** checkpoint. Final review covers the complete public feature and device evidence.

- [ ] Write measurement-runner tests before implementing its summary logic.

Expose `summarize_samples(samples: list[dict]) -> dict` from the new runner.
Each sample has `round`, `mode` (`plain` or `record`), `success`, `elapsedMs`
and `videoValid` (null for plain mode). Preserve failed attempts in raw output;
only complete successful pairs contribute to the median elapsed-time difference.
Return `totalSamples`, `failedSamples` and `pairedMedianDeltaMs`, with null for
the last field when no complete valid pair exists.

```python
def test_invalid_video_does_not_contribute_a_successful_pair(self):
    samples = [
        dict(round=0, mode="plain", success=True, elapsedMs=100, videoValid=None),
        dict(round=0, mode="record", success=True, elapsedMs=120, videoValid=False),
        dict(round=1, mode="plain", success=True, elapsedMs=200, videoValid=None),
        dict(round=1, mode="record", success=True, elapsedMs=240, videoValid=True),
    ]
    summary = summarize_samples(samples)
    self.assertEqual(4, summary["totalSamples"])
    self.assertEqual(1, summary["failedSamples"])
    self.assertEqual(40, summary["pairedMedianDeltaMs"])
    self.assertEqual(4, len(samples))
```

Also test missing pairs, failed subprocesses, duplicate round/mode rejection,
phase arithmetic and input preservation. Use deterministic numeric samples,
not wall-clock thresholds. Keep the module importable without device access.

- [ ] Implement the runner, update documentation and collect device evidence.

Document `--record`, both output formats, Android initialization versus iOS
first-frame readiness, required video semantics, error precedence, setup's
single-target assumption, Android duration capability rejection, the 10000 ms
startup ceiling and the extra total 30000 ms finalization budget. Keep distributed
agent instructions self-contained. Do not claim runtime backend-identity checks.

Use agentico:visiontest for real Android and iOS Simulator checks against the
repository benchmark fixture. The flow must show an observable screen transition
and an explicit success marker. Inspect the resulting videos with platform video
tools or a viewer to verify the transition, not merely their file size. Verify
startup failure dispatches no actions and timeout still yields a saved recording.

Collect paired with/without-recording samples with warmups and alternating mode
order. Record source commit, JAR hash, native/OS/tool versions, resolution/fallback,
target, commands, video validation, failures and phase timings. Use performance
traces or harness timestamps for separate startup/execution/finalization values;
do not add timing fields to the public recording report. Retain failed attempts.
The preliminary static-screen numbers in the spec are not full-flow benchmarks.
If a target cannot be exercised, report the exact blocker and leave device
verification pending rather than substituting fabricated samples.

- [ ] Verify and commit.

Run `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s benchmarks/tests -p 'test_recording_probe.py' -q`.
Commit the listed files and actual evidence with
`test: verify and document recorded flow behavior`.

At the final checkpoint, run `./gradlew :app:test`, `./gradlew :app:e2eTest`,
`./gradlew build` and `git diff --check`. Native iOS code is unchanged, so no new
Swift-method gate is introduced. Complete agentico:branch-review and then
agentico:finishing-a-development-branch. Do not push or open a PR without a user request.

## Spec coverage and self-review

| Spec requirement | Tasks |
| --- | --- |
| Activation, input modes, complete preflight and disabled behavior | 5, 6, 11 |
| Host-owned adapters, target/endpoint pinning and bounded discovery | 1, 3, 4 |
| iOS first-frame readiness and Android initialized-container readiness | 2, 3, 4 |
| Duration capability and rounded configured budgets | 4, 5 |
| Startup failure, stable skipped IDs and no startup evidence | 6 |
| Asynchronous zero/nonzero exit, dispatch uncertainty and committed results | 7 |
| Final observations, evidence and first-error precedence | 5, 8 |
| Required final artifact, conditional JSON and text output | 2, 5, 9, 11 |
| Timeout, cancellation, shutdown and one finalization budget | 1, 3, 4, 10 |
| Executable scenarios and complete scenario registration | 5–10 |
| Packaged output isolation | 11 |
| Documentation and paired device evidence | 12 |

The six feature scenarios map one-to-one to Tasks 5–10. All tasks are checkpoints.
No task changes native JSON-RPC methods, MCP tool names, dependencies or quality
baselines. Task interfaces retain existing default parameters and shared step
closures. Selection parity means ordering/eligibility parity, not reuse of unsafe
standalone discovery processes or their retry delays. The feature's final approval
starts execution; this plan does not claim that its scenarios already pass.
