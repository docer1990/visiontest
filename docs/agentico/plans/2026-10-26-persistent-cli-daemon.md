# Persistent CLI Daemon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development or agentico:executing-plans to implement this plan task-by-task.

**Goal:** Automatically reuse a local Kotlin host across independent CLI commands while preserving results and caller context.

**Architecture:** A thin entry point connects through a private Unix socket to a serialized host. Each request gets a fresh parser and explicit context; existing registrars and flow execute against compatible retained components. Local help, version, init, MCP and explicit standalone execution remain separate entry paths.

**Tech Stack:** Kotlin/JVM 17, existing Clikt, kotlinx.coroutines, kotlinx.serialization JSON, JDK Unix sockets and file locks, JUnit 5, Python benchmark harness.

**Branch:** feat/persistent-cli-daemon off main (2d95532)

**Decisions:** TD-003, TD-015, TD-016, TD-018, TD-020

**Contract:** `docs/agentico/specs/2026-10-26-persistent-cli-daemon-design.md`

**Testing:** Plain TDD and process acceptance tests. No dependency changes or native RPC changes.

---

## File structure and boundaries

- `cli/CliRequestExecutor.kt` owns parsing and returns `CliResult` without process exit.
- `cli/CliExecutionContext.kt` carries cwd, environment, stdin, diagnostics and presentation for one invocation; it offers a scoped coroutine/thread binding for existing shared operations.
- `cli/VisionTestCli.kt` injects runners and components into fresh command definitions.
- `common/HostProcessContext.kt` applies explicit caller cwd/environment and executable lookup to host children.
- `common/RequestLogger.kt` routes operation diagnostics into the active request without swapping process streams.
- `performance/TraceRecorder.kt` provides request-scoped dispatch for retained components.
- `cli/ComponentHolder.kt` owns explicit close and optional process-hook registration.
- `daemon/DaemonComponents.kt` owns one compatible reusable graph and reports initialization counts.
- `daemon/DaemonTargetGuard.kt` checks supported single-target topology after command validation.
- `daemon/DaemonProtocol.kt` and `DaemonFrames.kt` implement bounded message encoding and framing.
- `daemon/DaemonRegistry.kt` owns private paths, permissions, lock, build identity and metadata.
- `daemon/DaemonHost.kt` owns request admission, execution, cancellation, idle expiry and cleanup.
- `daemon/DaemonClient.kt` owns discovery, startup, connection, stdin/output relay and failure certainty.
- `daemon/DaemonLauncher.kt` and `CliRouting.kt` implement lightweight process entry and lifecycle commands.
- `Main.kt` remains the process exit and MCP entry boundary.
- `benchmarks/daemon.py` and its tests compare standalone and daemon process populations.
- Focused tests mirror each responsibility; `DaemonE2ETest` tests the packaged JAR with isolated runtime paths.

Paths above are relative to `app/src/main/kotlin/dev/visiontest/` unless prefixed
with `benchmarks/`. Existing public constructor signatures remain available.

## Task 1: Return CLI results without terminating the host

**Files:** Create `app/src/main/kotlin/dev/visiontest/cli/CliRequestExecutor.kt` and
`CliExecutionContext.kt`; modify `Main.kt`, `cli/VisionTestCli.kt` and
`cli/CliErrorHandler.kt`; test `app/src/test/kotlin/dev/visiontest/cli/CliRequestExecutorTest.kt`.

**Review:** checkpoint. All later tasks depend on the request execution boundary.

Add an internal `CliExecutionContext` with absolute `workingDirectory: Path`,
`environment: Map<String, String>`, `readStdin: () -> String` and diagnostic sink.
Its `local()` factory snapshots the calling process. Add internal
`CliRequestExecutor.execute(args: Array<String>, context: CliExecutionContext,
components: (() -> ComponentHolder)? = null, executionContext: CoroutineContext =
EmptyCoroutineContext): CliResult`. An entry timestamp may be supplied internally
to preserve trace preparation timing. Preserve existing public constructors.

Start with tests that call execute twice in one JVM, first invalid and then valid
help/version, and prove no exit or component initialization. Use a throwing
component provider. For example, the test body must exercise real parsing:

```kotlin
val context = CliExecutionContext.local()
val executor = CliRequestExecutor()
val invalid = executor.execute(arrayOf("get_device_info"), context,
    components = { error("must not initialize") })
assertEquals(2, invalid.exitCode)
val version = executor.execute(arrayOf("--version"), context,
    components = { error("must not initialize") })
assertEquals(0, version.exitCode)
assertTrue(version.stdout.orEmpty().contains(VersionInfo.version))
```

Also test usage formatting, Android-only rejection, returned operation failures,
flow result exits and diagnostics, help/version and per-request parser state.
Reuse mocked holders from `CliCommandIntegrationTest` where operations are needed.

Inject result-returning runners into VisionTestCli. Extract the exact help/error
formatting from Main and preserve newline behavior. Only outer entry gateways
print and exit. The executor owns trace finalization and does not register a new
shutdown hook per request. Retain the existing standalone component lifetime for
now. Coroutine context must reach nested command runBlocking calls so later host
cancellation can cancel an active request. Do not change default routing yet.

Run `./gradlew :app:test --tests '*CliRequestExecutorTest' --tests '*CliErrorHandlerTest' --tests '*VisionTestCliTest' --tests '*MainDispatchTest' --tests '*CliConstructorCompatibilityTest' --console=plain`.
Expected failure first: missing executor; final result: all selected tests pass.
Commit `refactor: return CLI request results without process exit`.

## Task 2: Isolate caller paths, input, environment, diagnostics and tracing

**Files:** Modify `cli/CliExecutionContext.kt`, `cli/CliRequestExecutor.kt`,
`cli/VisionTestCli.kt`, `cli/FlowInput.kt`, `cli/commands/FlowCommand.kt`,
`tools/ScreenshotSaver.kt`, `discovery/ToolDiscovery.kt`, `performance/TraceRuntime.kt`,
`performance/TraceRecorder.kt`, `utils/ErrorHandler.kt`, `config/AppConfig.kt`;
create `common/HostProcessContext.kt`, `common/RequestLogger.kt`; adapt host process
creation in `android/Android.kt`, `ios/ProcessExecutor.kt`, automation registrars
and `recording/RecordingProcessRunner.kt` and logger creation in shared host code.
Tests: `cli/CliRequestContextTest.kt`, `common/HostProcessContextTest.kt`,
`common/RequestLoggerTest.kt`, `performance/RequestTraceTest.kt`.

**Review:** checkpoint. Retaining components is unsafe before these boundaries exist.

Use a scoped ThreadLocal plus coroutine `asContextElement` for request context;
restore it in finally and do not mutate global streams/properties/environment.
Expose `resolve(path: String): Path`, `environmentValue(name: String): String?`,
and internal current-context access with process defaults outside CLI execution.
Resolve flow files, artifacts, default/explicit screenshots, trace output and
discovery search roots against the caller. Preserve Clikt argument-file behavior
with caller-relative resolution, including nested files and escaping, without
scanning typed command data as launcher switches. Supply flow stdin lazily.

Use explicit ProcessBuilder directory/environment and caller PATH resolution for
child executable names. A caller may supply any environment needed by existing
tools; it must not be written to metadata/logs. Environment maps are request data,
and resource compatibility compares relevant tool configuration before reuse.

RequestLogger wraps the existing SLF4J logger interface and preserves configured
levels/formatting and throwable diagnostics in request stderr. Coroutine worker
logs route to the same request; non-request/MCP logging retains its current sink.
Remove ErrorHandler's assumption that one cached environment covers every caller.

Add `TraceRecorder.Contextual` dispatch through the active request's trace context
so components can be retained without holding the first invocation's sealed sink.
Keep ordinary recorder behavior intact. Only actual graph creation emits
component.init; later traced requests still capture existing operation spans.

Write tests using two temporary caller directories and fake executable scripts.
The same flow filename must read different contents in each caller. Screenshot,
trace and artifacts must land in their caller directories. Two environments must
reach child processes and discovery independently. Test request logger isolation
across Dispatchers.IO and successive trace sessions on retained components.

```kotlin
val first = CliExecutionContext(workingDirectory = firstDirectory,
    environment = mapOf("PATH" to firstBin.toString()), readStdin = { "first" })
assertEquals(firstDirectory.resolve("capture.png"), first.resolve("capture.png"))
assertEquals("first", first.readStdin())
```

Run `./gradlew :app:test --tests '*CliRequestContextTest' --tests '*HostProcessContextTest' --tests '*RequestLoggerTest' --tests '*RequestTraceTest' --tests '*FlowCommandTest' --tests '*ToolDiscoveryTest' --tests '*ScreenshotSaverTest' --tests '*TraceRuntimeTest' --tests '*ErrorHandlerTest' --console=plain`.
Add affected constructor/process tests if their implementation changes.
Expected first failure: caller paths/context use the process defaults; final: pass.
Commit `refactor: isolate CLI caller context and request diagnostics`.

## Task 3: Own reusable components and enforce supported target topology

**Files:** Modify `cli/ComponentHolder.kt`, `android/Android.kt`,
`tools/IOSAutomationToolRegistrar.kt`; create `daemon/DaemonComponents.kt` and
`daemon/DaemonTargetGuard.kt`; test `daemon/DaemonComponentsTest.kt`,
`daemon/DaemonTargetGuardTest.kt`, `cli/ComponentHolderLifecycleTest.kt`.

**Review:** checkpoint. The daemon host consumes the resource and admission interfaces.

Make ComponentHolder explicitly AutoCloseable with idempotent cleanup and a factory
mode that installs no process hook. Keep standalone constructors/factories source
compatible. Close owned iOS child processes on daemon graph disposal; do not kill
external xcodebuild or shared ADB/native servers. Preserve iOS stop ownership across
successive requests. Flow recording retains its existing bounded cleanup.

`DaemonComponents` exposes an internal request provider compatible with Task 1,
an initialization counter and `close()`. Retain one graph with Contextual tracing.
Invalidate incompatible environment/tool configuration, target change/disconnect
and failed transport only after outstanding execution settles. A newly created
graph has no request-specific fields or hooks. Validate before provider access.

`DaemonTargetGuard` checks fresh inventories after ordinary validation and before
device operations. Android permits one available serial and checks that a present
9008 forward belongs to it. iOS permits one booted simulator. Missing target maps
to exit 4; ambiguous topology maps to exit 1 with an actionable diagnostic.
`available_device` remains a listing operation and is exempt from selection guards.
Expose testable inventory/forward callbacks, not production test bypass flags.
Document that fixed-port iOS lacks a backend identity handshake; the enforced
single-booted-simulator topology is a restriction, not verified multi-device routing.

Write tests proving identical compatible requests return the same graph, invalid
commands do not call its provider, configuration replacement closes once, and
closing twice closes owned resources once. Test zero/one/multiple targets, fresh
inventory after change, mismatched adb forwarding and listing exemption.

```kotlin
val constructions = AtomicInteger()
val holder = mockk<ComponentHolder>(relaxed = true)
val resources = DaemonComponents(factory = { constructions.incrementAndGet(); holder })
assertSame(resources.acquire(context), resources.acquire(context))
assertEquals(1, constructions.get())
resources.close()
resources.close()
verify(exactly = 1) { holder.close() }
```

Provide `acquire(context: CliExecutionContext): ComponentHolder` and injectable
`factory: () -> ComponentHolder`; additional compatibility inputs are internal.
Run `./gradlew :app:test --tests '*DaemonComponentsTest' --tests '*DaemonTargetGuardTest' --tests '*ComponentHolderLifecycleTest' --tests '*IOSAutomationToolRegistrarTest' --tests '*Android*Test' --console=plain`.
Expected first failure: resource owner absent; final: selected tests pass.
Commit `feat: retain compatible daemon resources with target guards`.

## Task 4: Frame local requests and manage private daemon registration

**Files:** Create `daemon/DaemonProtocol.kt`, `daemon/DaemonFrames.kt`,
`daemon/DaemonRegistry.kt`; tests mirror these files under `app/src/test/kotlin/dev/visiontest/daemon/`.

**Review:** checkpoint. Wire format and access control are public contract boundaries.

Use existing kotlinx.serialization JSON without a new dependency/plugin. Model
hello/ready, execute, stdin-request/input, stdout/stderr chunks, completion, cancel,
status and stop messages. Every execution message includes request identity;
hello includes protocol and application build identity. Return operation stdout
without framing at the client boundary. Protocol frames use a four-byte big-endian
length and enforce 8 MiB maximum input before allocation. Chunks are at most 64 KiB.
Set an explicit finite total output bound and return an infrastructure error if it
is exceeded, preserving dispatch uncertainty and never a truncated success object.

`DaemonFrames.read(input: InputStream): JsonObject?` returns null only at clean EOF;
truncated headers/bodies, negative/oversized lengths and invalid JSON throw IO errors.
`DaemonFrames.write(output: OutputStream, message: JsonObject)` writes one frame.

```kotlin
val bytes = ByteArrayOutputStream()
val message = buildJsonObject { put("kind", "status") }
DaemonFrames.write(bytes, message)
assertEquals(message, DaemonFrames.read(ByteArrayInputStream(bytes.toByteArray())))
assertFailsWith<IOException> {
    DaemonFrames.read(ByteArrayInputStream(byteArrayOf(127, -1, -1, -1)))
}
```

Runtime defaults to a private user state directory with a short Unix socket path.
`VISIONTEST_DAEMON_DIR` overrides it for isolated tests and explicit operational
isolation; document that separate instances must not concurrently drive one target.
Use NOFOLLOW_LINKS checks, owner identity and POSIX 0700/0600 permissions. Reject
unsafe existing paths instead of chmodding unrelated files. The daemon holds a
FileChannel lock for its lifetime; only its holder can remove stale registration.
Publish ready metadata atomically after bind. Build identity includes JAR contents
or an equivalent build fingerprint, not just the version string. Cache fingerprint
within each process; measure its client overhead. Status never initializes devices.

Test symlinks, permissive directories, malformed frames, version/build mismatch,
same-version differing builds, lock contention and stale cleanup. Do not kill PIDs
read from untrusted/stale metadata. Keep live incompatible owners intact.
Run `./gradlew :app:test --tests '*DaemonProtocolTest' --tests '*DaemonFramesTest' --tests '*DaemonRegistryTest' --console=plain`.
Expected first failure: absent codec; final: all selected tests pass.
Commit `feat: add private versioned daemon transport and registry`.

## Task 5: Execute serialized daemon requests and bound their lifetime

**Files:** Create `daemon/DaemonHost.kt` and focused lifecycle/admission helper files
if needed; test `daemon/DaemonHostTest.kt`, `daemon/DaemonCancellationTest.kt`.
Integrate Tasks 1–4 without modifying native protocols.

**Review:** checkpoint. The forwarding client relies on these lifecycle semantics.

The host accepts Unix connections, performs compatibility negotiation before work,
then executes a fresh CLI parser with request context and a retained component
provider. Implement one active device request, at most 32 queued requests and a
30-second queue deadline. Status/cancel/stop remain responsive while work executes.
Use finite framing/readiness/execution waits; ordinary operations have a bounded
host watchdog, and flows retain their up-to-one-hour execution plus finalization
budgets. Do not put a shorter fixed socket timeout around a valid long flow.

Request stdin only when FlowCommand's lazy reader is actually called. Bound input
reads and propagate EOF/cancel. Route result and diagnostics to the owning socket.
Do not stream partial JSON as a successful operation result. Client EOF cancels
queued work or requests active cancellation; it never causes replay. Wait for
host cleanup before releasing execution admission. If dispatched native work has
unknown completion, mark the affected target unsafe for further mutations until
the user explicitly stops/restarts the native backend and daemon.

Stop closes admission, waits up to 30 seconds, cancels remaining work and closes
owned resources exactly once. Idle expiry is 15 minutes only with no active/queued
requests. Inject a clock and durations for fast deterministic tests. Bound request
retention, connection handlers and log storage; do not retain completed payloads.

Use fake execute callbacks and barriers to assert two requests never overlap and
a whole flow cannot be interleaved. Assert queue expiry never calls the callback,
disconnect after callback entry never calls twice, stop cleans once and idle does
not expire during work. For cancellation uncertainty assert the next mutation is
rejected while status/stop still work. Test stderr remains with its request.

Run `./gradlew :app:test --tests '*DaemonHostTest' --tests '*DaemonCancellationTest' --console=plain`.
Expected first failure: host absent; final: deterministic lifecycle tests pass.
Commit `feat: coordinate persistent daemon requests and cancellation`.

## Task 6: Route ordinary CLI commands through the thin automatic client

**Files:** Create `daemon/DaemonClient.kt`, `daemon/DaemonLauncher.kt`,
`daemon/CliRouting.kt`; modify `Main.kt` and CLI root help; tests
`daemon/CliRoutingTest.kt`, `daemon/DaemonClientTest.kt`, `daemon/DaemonE2ETest.kt`.
Adapt existing packaged-JAR parity fixtures to explicitly select standalone mode
where they deliberately mock only HTTP and have no device inventory.

**Review:** checkpoint. This changes the default public CLI execution path.

Recognize root --standalone and lifecycle commands before constructing Clikt.
Never interpret tokens after the subcommand/-- as launcher flags. Preserve root
--trace-performance value consumption, --help/-h, --version, init, empty args and
serve routing. Forward unknown/malformed device invocations for authoritative host
validation without initializing devices. Resolve ambiguous argument-file routing
through the local parser if needed; do not silently reinterpret its contents.

Automatically connect or start the same application artifact as a detached daemon.
Use the current Java executable/classpath with explicit hidden host entry point;
do not invoke a shell. Race-safe startup waits at most 15 seconds. Existing live
incompatible daemons fail with instructions for explicit stop/restart. Relay stdin
only after host demand and relay stdout/stderr/exit exactly. Never retry submission
or fall back after it could have reached the host. Connection loss reports that
execution may have occurred. Interrupt sends cancellation and closes the socket.

Expose daemon start/status/stop locally with stable text and --json status output.
Help/version/init do not need a daemon. Main loads heavy CLI/MCP classes only on
their chosen route. Test class loading or instrumentation evidence that a forwarding
client does not initialize Clikt/Mordant or ComponentHolder.

```kotlin
assertEquals(CliRoute.MCP, classifyCliArguments(emptyArray()).route)
assertEquals(CliRoute.STANDALONE,
    classifyCliArguments(arrayOf("--standalone", "get_device_info", "-p", "ios")).route)
assertEquals(CliRoute.DAEMON,
    classifyCliArguments(arrayOf("input_text", "-p", "ios", "--", "--standalone")).route)
```

Introduce `CliRoute` and `classifyCliArguments(args: Array<String>)` in CliRouting;
the returned routing record includes forwarded arguments. Ensure process fixtures
set a temporary VISIONTEST_DAEMON_DIR and cleanup only that daemon in finally.
Use existing fake executable patterns for target inventory and real MockWebServer
responses. Prove two independent JVMs report the same daemon PID and graph count.

Run `./gradlew :app:test --tests '*CliRoutingTest' --tests '*DaemonClientTest' --tests '*MainDispatchTest' --console=plain` and
`./gradlew :app:e2eTest --tests '*DaemonE2ETest' --tests '*CliE2ETest' --tests '*PerformanceTraceE2ETest' --tests '*McpStdioE2ETest' --console=plain`.
Expected first failure: ordinary route remains standalone; final: parity and reuse pass.
Commit `feat: automatically reuse daemon for device CLI commands`.

## Task 7: Exercise failure races and publish lifecycle and measurement evidence

**Files:** Extend `daemon/DaemonE2ETest.kt` and add focused adversarial process test
files; create `benchmarks/daemon.py`, `benchmarks/tests/test_daemon.py`,
`docs/benchmarks/daemon.md`; update `README.md`, `CONTRIBUTING.md`,
`docs/agentico/specs/cli.md`, `docs/agentico/specs/server-lifecycle.md`,
`app/src/main/resources/agent-instructions.md`, `benchmarks/README.md`.

**Review:** checkpoint. Final acceptance and public documentation.

Add real-process cases for simultaneous startup, stale registration, incompatible
build, idle expiry, two caller cwd/env/stdin/trace contexts, native restart, target
change, queue timeout, disconnect and daemon death after a mutation dispatch. Assert
native request counts, preserved confirmed flow steps and zero replay. Add repeated
requests proving bounded holders/hooks/retained payload counts. Run legacy output
fixtures in standalone and daemon modes with equivalent fake inventories/backends.

Benchmark commands and flows with identical parameters and reset fixtures; separate
cold host, first use and subsequent fresh JVM clients. Raw JSONL samples include
wall duration, mode, command identity, exit, timeout/uncertainty and RSS. Report
median/p95, failures, idle RSS and repeated-request memory. Attribute admission
checks separately and do not hide changed native request counts. Unit-test summary
math and fake command execution without hardware-dependent speed thresholds.

```python
from benchmarks.daemon import summarize_durations
self.assertEqual(summarize_durations([1.0, 2.0, 3.0])["median"], 2.0)
```

Provide `summarize_durations(values: list[float]) -> dict[str, float]` and a module
CLI documented with reproducible Android/iOS fixture commands. Run available real
fixture measurements and record environment, raw samples and limitations. If no
eligible target is available, state the evidence gap; do not invent a speedup.

Document automatic default, --standalone, lifecycle/idle/ownership, version
upgrades, caller environment, single-target restrictions and recovery after
uncertainty. Distributable guidance stays self-contained. Update contracts for
new lifecycle exceptions to the device-platform rule and changed routing.

Run `python3 -m unittest benchmarks.tests.test_daemon -q` while iterating, then the
repository gate `./gradlew :app:test`, `./gradlew :app:e2eTest`, `./gradlew build`,
`python3 -m unittest discover -s benchmarks/tests -q` and `git diff --check`.
Expected: tests pass, no new detekt/lint findings or lowered coverage floors.
Commit `test: verify daemon compatibility and document measured behavior`.

## Review and coverage audit

Tasks 1–2 cover request isolation and existing command contracts. Task 3 covers
resource ownership and target admission. Tasks 4–5 cover IPC, startup ownership,
bounded lifetime and uncertainty. Task 6 covers default migration and thin-client
execution. Task 7 covers adversarial acceptance, measurements and documentation.
Every task is a checkpoint because later code or users depend on its contract.
After Task 7 run branch-review against main, address findings and use the finishing
skill. Do not publish or merge without the user's authorization.
