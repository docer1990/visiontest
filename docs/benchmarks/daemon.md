# Automatic CLI daemon: behavior and measurement

Ordinary device commands start and reuse a per-user Kotlin host automatically.
Each invocation still starts a fresh client JVM. The client forwards arguments and
caller context; the host parses the existing CLI and calls the same registrars as
standalone CLI and MCP. UI observations, target inventory and native health are
fresh. Reusing a host does not cache UI results.

## Operation and recovery

```bash
visiontest daemon start
visiontest daemon status --json
visiontest get_device_info -p android --json
visiontest --standalone get_device_info -p android --json
visiontest daemon stop
```

Lifecycle commands require no platform or backend. Root help/version and `init`
remain local; no arguments or first-argument `serve` starts MCP. Ordinary device
commands still require `-p android|ios`. `--standalone` is a root option and keeps
execution in the caller JVM. It does not provide automatic failure recovery.

The host serializes complete commands and flows, allows 32 queued requests and
expires their wait after 30 seconds. It exits after 15 idle minutes. Stop rejects
new work, drains accepted work for up to 30 seconds, then cancels and performs
bounded cleanup. The host retains its lock until active work and cleanup settle.
Cancellation or client disconnect does not establish native cancellation.

The private user-owned socket directory defaults to a short path under `/tmp`.
`VISIONTEST_DAEMON_DIR` can select an absolute private directory for explicit
isolation. Different directories do not coordinate the fixed native endpoints;
never use separate instances concurrently on one target. Do not kill a PID from
registration metadata. Stale registration is reclaimed only under the instance
lease. A live incompatible build is reported with explicit stop/restart guidance;
a matching protocol still permits status/stop. After upgrading, stop the old host
and retry the operation explicitly.

Each request carries its own cwd, environment, stdin, terminal dimensions and trace
configuration. Relative input, argument files and artifacts resolve against that
cwd. Toolchain or artifact configuration changes can replace the retained graph.
The daemon accepts exactly one usable Android device or one booted iOS simulator.
Android forwarding and an explicit `ANDROID_SERIAL` must agree with that device.
Nondefault ADB endpoints require `--standalone`. Diagnostic `available_device` and
`automation_server_status` remain exempt from inventory admission; iOS owned stop
also works after simulator disconnection. The iOS fixed-port backend has no
identity handshake, so inventory admission does not prove backend identity.

Only daemon clients share its execution boundary. Serialize external standalone
and MCP work yourself. A response lost after dispatch may mean an action completed:
inspect app state, retain confirmed flow steps, and never replay automatically.
When completion is unknown, restart the native backend and daemon explicitly
before further mutations. Daemon disposal closes owned iOS startup children and
host resources. Android instrumentation remains separately controlled by
`stop_automation_server`. Never stop unrelated xcodebuild processes or ADB servers.

## Reproduce a paired collection

Use Python 3.11+, JDK 17+, the built JAR, exactly one Android device or booted iOS
simulator, and matching native automation servers. Build/install the dedicated
`dev.visiontest.benchmark` fixtures using the [fixture guide](../../benchmarks/fixtures/README.md)
and start the native server using the [contributor guide](../../CONTRIBUTING.md).
Do not run process E2E tests or other automation while the collection owns the fixed
native endpoints. Save the native artifact versions, target ID/OS/model and setup
commands with the output. Only reset or relaunch the synthetic fixture, never user
apps. Freeze the JAR until all modes complete: its embedded instructions contribute
to build identity.

```bash
./gradlew :app:shadowJar
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest benchmarks.tests.test_daemon -q
ANDROID_HOME="$HOME/Library/Android/sdk" PATH="$HOME/Library/Android/sdk/platform-tools:$PATH" \
  python3 -m benchmarks.daemon --platform android \
  --target SERIAL --output benchmarks/daemon-results/android --repetitions 10
python3 -m benchmarks.daemon --platform ios \
  --target SIMULATOR_UDID --output benchmarks/daemon-results/ios --repetitions 10
```

The runner creates an isolated private daemon directory and stops that host in
cleanup. It does not start/stop native servers, install apps or shut down devices.
An explicit `--target` must match the sole active inventory entry. Before every
timed sample it verifies that `dev.visiontest.benchmark` is installed, terminates
and relaunches only that synthetic fixture with `navigation en`, and observes its
ready marker and expected controls through the existing readiness helper. These
probes always use `--standalone`, so they do not initialize or warm the daemon's
component graph before its first device request.

Its two read-only workloads have identical arguments across
modes: `get_device_info --json` and a flow containing two `wait_for_element --resource-id SELECTOR --timeout 5000`
steps. The selector is `dev.visiontest.benchmark:id/start` on Android and `start`
on iOS, where each step also scopes `--bundle-id dev.visiontest.benchmark`.
Information queries and waits leave fixture state unchanged; untimed resets give
paired modes the same initial scenario. Setup command arguments, subprocess results,
duration and errors are included in each sample. Failed setup skips the measured
command and increments `setup_failures`; it never contributes successful latency.
Setup native calls are explicitly untraced and excluded from reported operation
HTTP/health counts. Readiness warms the native backend in both modes; first-use
means first use of the daemon graph, not first use of the native backend. This
pilot measures information and settled element waits;
it does not establish latency for screenshots, recordings or mutations.

Every sample is a separate client process. `cold_host` measures explicit daemon
startup without a device operation; `first_use` measures the first device request
in the new host; `subsequent` measures later fresh clients sharing that host. The
standalone first sample is reported separately too. No warmup is silently discarded.
The runner records raw wall nanoseconds/milliseconds, command arguments, mode,
phase, exit code, stdout/stderr, timeout and uncertainty. Complete request traces
supply native HTTP and health counts plus host invocation, component initialization
and CLI preparation nanoseconds. Missing/incomplete traces produce null metrics,
not claims of zero requests. Traces are opt in in both modes; this collection
therefore includes tracing overhead equally.

Admission is reported separately as ADB/simctl spans outside operation ancestry,
including their count and duration. These intervals remain included in wall time;
no health or admission check is subtracted to claim a speedup. `native_http_count`
counts traced HTTP exchanges; `health_count` counts health spans separately.
Inspect the raw trace if nesting or transport behavior changes. Summary median and
p95 use successful subsequent samples only; failures, timeouts and uncertain
results stay in the raw file and summary counts. p95 uses nearest rank, so a
10-sample pilot's p95 is its maximum. `summarize_durations([1, 2, 3])['median'] == 2`.

RSS is the daemon resident set from `ps`, in KiB, after each completion and after
one second of quiescence. It is not peak client RSS or a retained-heap measurement.
The repeated series makes growth visible without asserting a hardware-dependent
memory or latency threshold. Cold-start and first-use samples remain in JSONL
alongside idle RSS. `environment.json` records host/JVM/Python, git/worktree and
JAR SHA-256 provenance; `summary.json` reports median/p95, failures and RSS series.

## Acceptance coverage

The packaged-process suites use private runtime directories and fake inventories
and native backends. `DaemonAdversarialE2ETest` covers simultaneous startup, stale
crash registration, a live incompatible handshake, idle expiry, independent caller
cwd/environment/stdin/trace contexts, native restart, target replacement, queue
expiry, queued disconnect, a lost flow mutation response and host death after
mutation dispatch. They assert exact native counts, preservation of earlier flow
steps and zero automatic replay. Existing `DaemonE2ETest` also compares legacy
structured output with standalone execution and proves thin-client class loading,
PTY dimensions, stdio behavior, graph reuse and process-group independence.

`DaemonRetentionE2ETest` uses a separate test-only host with the real retained graph
and synthetic 1 MiB responses. Seventeen fresh clients must retain one holder,
constant shutdown-hook count, and at most one completed request/input envelope
in a forced-GC class histogram. This tests ownership and payload retention without
claiming a production RSS bound. Lifecycle/unit tests cover bounded handlers,
output budgets and cancellation boundaries. Production limits and routing have no
test bypass flags.

```bash
./gradlew :app:e2eTest --tests '*Daemon*E2ETest'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest benchmarks.tests.test_daemon -q
```

The final repository gate additionally runs `:app:test`, `:app:e2eTest`, `build`,
the entire Python benchmark suite and `git diff --check`.

## Local fixture measurements, 2026-10-28

The checked-in [Android samples](../../benchmarks/daemon-results/android/samples.jsonl)
and [iOS samples](../../benchmarks/daemon-results/ios/samples.jsonl) contain 96 rows:
88 timed operation attempts plus four cold-host and four idle-RSS observations.
All 88 operations completed successfully with complete traces; failures, setup
failures, timeouts and uncertainty were zero. Each flow reported both steps
`succeeded` with `confirmed` certainty. There are ten subsequent samples in each
mode/workload group, plus separately retained first-use samples.

The host was macOS 27.0.1 on arm64, Corretto 17.0.18.9.1 and Python 3.14.7.
Android used emulator-5554 running Android 17; iOS used an iPhone 17 simulator
running iOS 27.0. Both platforms used the same frozen JAR SHA-256:
`c2e2a2281154376458631a6cfc641b57cf430d77ef3b47c5b66bdb521c30206b`.
The environment files record the base git commit and dirty worktree used to build
it. [Android provenance](../../benchmarks/daemon-results/android/native-provenance.json)
and [iOS provenance](../../benchmarks/daemon-results/ios/native-provenance.json)
record installed APK and runner/fixture binary hashes; they do not assume that
an old local APK matches the installed artifact. The iOS synthetic fixture was
rebuilt with a window scene after its old AppDelegate-only bundle crashed on this
runtime. Its selectors, scenarios and launch arguments were preserved.

Subsequent fresh-client wall time, in milliseconds:

| Platform/workload | Standalone median | Standalone p95 | Daemon median | Daemon p95 |
|---|---:|---:|---:|---:|
| Android information | 298.0 | 848.2 | 184.0 | 191.2 |
| Android two waits | 812.8 | 1298.3 | 217.0 | 228.0 |
| iOS information | 281.9 | 381.3 | 245.7 | 255.2 |
| iOS two waits | 876.8 | 1145.5 | 350.6 | 358.3 |

These are local pilot observations, not a hardware-independent speed guarantee.
The narrower iOS information improvement includes the full simulator admission
cost. The ten-sample p95 is the maximum and is sensitive to host scheduling.

Cold host startup and first device use remain separate, each with one observation
per workload. These values do not measure a single automatic-start invocation;
startup has an additional cost that the warm-client table does not include.

| Platform/workload | Cold host start ms | First standalone ms | First daemon request ms |
|---|---:|---:|---:|
| Android information | 615.0 | 289.9 | 287.7 |
| Android two waits | 626.9 | 822.9 | 396.5 |
| iOS information | 682.7 | 623.8 | 359.5 |
| iOS two waits | 632.9 | 871.2 | 542.7 |

Native work was preserved: every information sample made one native HTTP request
and two health checks; every two-wait flow made two native HTTP requests and four
health checks, in both modes. Setup/readiness subprocesses are recorded separately
and make additional untraced native calls. Daemon admission remained visible:
Android ran two ADB probes per request; iOS ran one simctl probe. Standalone has
no daemon admission probes. No checks were removed to obtain these results.

Median traced host stages, in milliseconds. These are stage measurements rather
than independent end-to-end timers; retain their ancestry when analyzing overlap.
All subsequent daemon component-initialization spans were zero; standalone
initialized its graph for each fresh JVM (about 20–23 ms median).

| Platform/workload | Host invocation standalone/daemon | CLI prepare standalone/daemon | Daemon admission |
|---|---:|---:|---:|
| Android information | 80.5 / 55.1 | 138.3 / 8.4 | 25.9 |
| Android two waits | 211.0 / 88.6 | 160.7 / 14.8 | 25.9 |
| iOS information | 61.6 / 116.7 | 141.7 / 8.4 | 102.5 |
| iOS two waits | 266.6 / 223.4 | 167.3 / 15.3 | 98.2 |

Daemon RSS, in KiB. The raw files retain every intermediate observation; this
short series does not establish a steady-state memory bound.

| Platform/workload | First subsequent | Last subsequent | After one idle second |
|---|---:|---:|---:|
| Android information | 75152 | 83984 | 83952 |
| Android two waits | 105584 | 113472 | 113232 |
| iOS information | 92736 | 90448 | 90416 |
| iOS two waits | 114288 | 124368 | 124384 |

[Combined derived metrics](../../benchmarks/daemon-results/comparison.json) and
per-platform `summary.json` files support the tables. Earlier rejected Android
batches remain in the local audit directory
`/tmp/visiontest-issue86-rejected-daemon-measurements`: one used an unsupported
flow command and failed preflight; another used valid selectors but lost fixture
foreground for an unclassified reason. They are excluded from the committed
latency comparison. The successful collection added per-sample synthetic reset
and observed readiness, and retained every attempted sample.

The initial native iOS unit gate on this host exited 65 before test bootstrap:
UIKit required a scene lifecycle in the `IOSAutomationServer` host app. The app
now creates its existing UI in a window scene. A later gate passed 111 native unit
tests and two finite UI tests covering the host label and alert. The measurements
above used the separately identified prebuilt automation runner and scene-based
fixture; their provenance and measured values were not changed by this host-app
compatibility fix.
