# HTTP command baseline: partial collection

The local collection was stopped at the user’s request on 2026-10-19. One of the eight full configurations completed: iOS CLI with a warm native backend. Its 560 attempts all passed, including 480 measured attempts and 80 warmups. All 280 traced attempts have complete attribution. The other seven configurations remain incomplete; the 57C delivery and issue #57 remain open.

The eight-configuration preflight passed 128 attempts across Android/iOS, CLI/MCP and warm/host-cold. Preflight is preparation evidence, not a substitute for the full matrix. Both native fixture smoke suites passed five tests. The command and fixture harness fixes, CI-independent tests and raw evidence are preserved.

## Environment and scope

The completed run uses runner `5a0a7b1`, the current built JAR/native timing bundle, and the `fixture-v2-settled` fixture. Its manifest records artifact SHA256 values and fixture tree revision. The host is a MacBook Pro Mac16,8 with an Apple M4 Pro (12 cores), 24 GB RAM and macOS 27.0.1 (26A434). The JVM is Amazon Corretto 17.0.18.9.1; Python is 3.14.7. The target is an iPhone 17 simulator running iOS 26.5 over localhost HTTP. Both that simulator and the Android API 37 emulator were running while benchmark commands executed sequentially on an interactive development host.

Collection pins a 20-second per-case timeout. An earlier run using the template’s 8–10 second limits had one isolated warmup readiness timeout and was stopped for diagnosis. Sixty traced fixture resets then passed without reproducing it. The cause of the isolated delay remains unconfirmed. That original partial run is retained separately, including its failed attempt.

## Recorded runs

| Configuration | Attempts | Passed | Warmups | Measured | State | Evidence |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| ios-cli-warm | 96 | 95 | 20 | 76 | Partial, interrupted | [run](../../benchmarks/results/baseline-90d8c008-d4b0-4c0c-9669-8d0119dfbff6) |
| ios-cli-warm | 560 | 560 | 80 | 480 | Complete configuration | [run](../../benchmarks/results/baseline-a1b1b964-cc71-4ae1-8031-022dabf736c6) |
| ios-cli-host-cold | 49 | 49 | 10 | 39 | Partial, interrupted | [run](../../benchmarks/results/baseline-e69ff743-dff3-46e1-807b-a624d3966dbc) |

The two interrupted runs preserve all persisted records and traces. An in-flight setup or operation at interruption may lack a final sample; each provenance file records this gap. Runs with different time limits or runner revisions are not pooled into latency distributions. The host-cold run was stopped by the user after 49 passed attempts.

All eight preflight runs and the three collection runs are listed in the [evidence index](../../benchmarks/results/collection-index.json). Each includes its exact local manifest verified against the recorded SHA256.

## iOS CLI warm measurements

Only the complete configuration contributes to the following tables. Each tracing state has 30 measured attempts per case; its five warmup pairs remain separate. Times are milliseconds. Median and nearest-rank p95 cover timed measured attempts. Paired overhead is the median signed on-minus-off delta and median per-pair percentage; it is not the difference of the two displayed medians.

CLI process duration includes launch/JVM startup and teardown/trace drain. CLI operation-only paired overhead is unavailable. These figures describe a local baseline and tracing overhead, not an optimization speedup or a CLI-versus-MCP comparison.

| Case | Off median ms | Off p95 ms | On median ms | On p95 ms | Paired delta ms | Paired % |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| information | 251.590 | 310.684 | 272.333 | 367.330 | 25.430 | 10.46 |
| lookup | 293.775 | 367.532 | 320.937 | 367.924 | 24.343 | 8.33 |
| interaction | 597.670 | 671.938 | 629.094 | 731.230 | 34.726 | 5.84 |
| appearance | 301.824 | 351.947 | 330.136 | 415.737 | 30.603 | 10.22 |
| disappearance | 267.607 | 346.162 | 296.249 | 377.743 | 27.227 | 10.33 |
| inspection | 294.536 | 387.920 | 305.877 | 404.153 | 9.987 | 3.43 |
| screenshot | 343.679 | 434.477 | 373.207 | 471.612 | 34.737 | 10.36 |
| missing-target | 253.113 | 264.014 | 277.208 | 293.422 | 25.605 | 10.21 |

## Measured contributors

The largest recorded host self-time stage is `http.exchange` for every case. It measures the host’s HTTP exchange interval, including native service time; it is not one-way network latency. The native `operation` stage is the largest recorded native self-time contributor. Other startup, preparation and teardown work remains outside these contributor populations.

| Case | HTTP exchange self median ms | Native operation self median ms | Samples per stage |
| --- | ---: | ---: | ---: |
| information | 17.569 | 6.892 | 30 |
| lookup | 63.500 | 52.137 | 30 |
| interaction | 367.425 | 346.341 | 30 |
| appearance | 65.702 | 55.392 | 30 |
| disappearance | 20.478 | 11.387 | 30 |
| inspection | 27.555 | 16.382 | 30 |
| screenshot | 96.326 | 82.198 | 30 |
| missing-target | 22.401 | 11.286 | 30 |

Per-stage inclusive/self median and p95 distributions remain in summary.json. Do not add inclusive parent/child spans or subtract host/native timestamps. HTTP minus native request duration is labeled a service-difference estimate. Native timing snapshots retain unexecuted or unsupported stages as gaps rather than measured zeros.

## Limits and pending work

Fixture readiness and its fixed 750 ms delayed transitions occur outside operation timing. The fixtures publish a stable marker before scenario inspection. This is a stable-state command baseline, not a measurement of agent flows or dynamic wait completion.

During earlier preparation, querying the transient iOS loading control while it disappeared produced XCTest snapshot failures and a server exit. The settled marker avoids that race in fixture setup; it does not fix the production transient-element lookup limitation. Earlier harness/parser diagnostic runs remain local and are not mixed with this evidence.

Native-cold was excluded. Branch review deferred native-cold restart ordering and a shared overall deadline for repeated MCP discovery cursors/notifications. Complete Android and MCP populations, the remaining host lifecycle configurations, and agent evaluation 57D are still pending. CI results must form a separate baseline from this Mac’s measurements.

## Verification

The runtime checkpoint passed 109 Python tests (104 passed, five device smoke tests skipped), 542 host unit tests, 12 host E2E tests, 176 Android debug unit tests, 108 Swift unit tests, the complete Gradle build, both native fixture builds, and five opt-in fixture smoke tests per platform. No coverage floor or lint baseline was relaxed.

## Prepare an isolated target

Run from the repository root with Python 3.11+, the JDK, and the platform SDK.
Use only the dedicated synthetic fixture. Keep exactly one Android device online
or exactly one iOS simulator booted for the selected platform. Save its serial or
UDID in a local manifest; the runner checks target isolation before every attempt.

Build the JAR and fixtures using the [fixture guide](../../benchmarks/fixtures/README.md).
Build/install the current Android automation APK pair or build the current iOS
XCUITest bundle using the [contributor guide](../../CONTRIBUTING.md#build-from-source).
Start that platform's automation server before smoke or collection. An older
installed server still runs commands but cannot supply a complete native baseline.
The runner installs/resets the fixture before each operation. Setup is outside
the operation timer, including the fixture's delayed transitions.

Keep local configuration and preliminary artifacts under ignored `test-output/`:

```sh
mkdir -p test-output/benchmarks
cp benchmarks/manifests/android.json test-output/benchmarks/android.local.json
cp benchmarks/manifests/ios.json test-output/benchmarks/ios.local.json
adb devices
xcrun simctl list devices booted -j
```

Edit each applicable copy's `target` to the single isolated serial/UDID. Verify
`fixtureArtifact` matches the built APK/app. Preserve the fixture identity and
scenario setup. Fill `fixtureRevision` with the tested fixture revision and fill
`environment` with actual build commit, installed native artifact versions,
connection type, target OS/model, JVM version, and host OS/hardware. Use a precise
`unavailableReason` when a fact cannot be collected; template placeholders are
not recorded hardware or version evidence. Keep launch-only environment values
in `processEnvironment`; do not put credentials into shared artifacts.

For iOS, the fixture guide's build destination can be changed to the isolated
simulator model that is installed locally; retain its OS/model and UDID in the
manifest/provenance. Do not silently change the target between paired attempts.

Run fixture smoke once per platform (change the manifest path for iOS):

```sh
VISIONTEST_BENCHMARK_SMOKE=1 \
VISIONTEST_BENCHMARK_MANIFEST=test-output/benchmarks/android.local.json \
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest benchmarks.tests.test_fixture_smoke -v
```

A failed smoke or setup is a collection blocker. Retain its diagnostics and fix
its cause before starting a fresh run; do not discard failed benchmark attempts
from an existing run or replay a timed-out operation.

## Collect both modes and host lifecycles

Run the following sequentially for each available platform, setting
`benchmark_platform` to `android` or `ios`. Do not run benchmark processes in
parallel. Each command runs all cases in that local manifest, five warmup pairs
and thirty measured pairs per case: 5+30 attempts **per tracing state**, 70 raw
attempts per case when setup succeeds. Pair order alternates off/on, then on/off;
each member receives its own fixture reset with the same pinned revision.

```sh
benchmark_platform=android
for benchmark_mode in cli mcp; do
  for benchmark_lifecycle in warm host-cold; do
    PYTHONDONTWRITEBYTECODE=1 python3 -m benchmarks.run commands \
      --manifest "test-output/benchmarks/${benchmark_platform}.local.json" \
      --output "test-output/benchmarks/${benchmark_platform}-${benchmark_mode}-${benchmark_lifecycle}-{run_id}" \
      --mode "$benchmark_mode" --lifecycle "$benchmark_lifecycle" \
      --samples 30 --warmup 5 --tracing paired
  done
done
```

The runner replaces the literal `{run_id}` token with its generated UUID and
writes that ID into `environment.json` and every raw sample. Output directories
must be fresh. Without that token, `--output` is used literally. Other text is
not interpolated. A nonzero exit leaves attempted samples available for review;
check each run's status before considering collection complete.

CLI always starts a fresh process, even under the `warm` label; this label keeps
the native backend warm. MCP `warm` reuses one initialized process for each
tracing state within a case. MCP `host-cold` starts and closes one server process
per attempt. Native-cold is omitted until an explicit, verified
`nativeLifecycle.stop`/`start` policy succeeds for the selected platform.

## Inspect and share evidence

Each run contains:

- `environment.json`: generated run ID, manifest SHA256, selected cases, fixture
  revision, platform/target, requested counts, and supplied environment metadata.
- `samples.jsonl`: all warmups, passed/failed/uncertain attempts, setup times,
  measured boundaries, pair order, trace references, and dispatch ledger.
- `summary.json`: actual counts and outcomes, separated warmup/measured groups
  by case, mode, lifecycle and tracing state; contributor and paired overhead
  populations retain their sample counts and gaps.
- `traces/*.jsonl`: one file per traced CLI attempt/host-cold MCP process, or one
  file per warm traced MCP case session, finalized before attribution.
- `artifacts/<sample_sequence>/capture.png`: separate screenshot files for each
  attempt, containing only the synthetic fixture. Samples retain a relative
  `screenshot_path`, including failed attempts; check status and file existence
  before using it. These are assertion artifacts, not proof of image consumption.

Share the applicable local manifests, each complete output directory, fixture
smoke output, and build/version provenance. Review metadata, failure diagnostics,
launch arguments, and screenshots for local paths or identifiers before sharing.
Retain all attempts and their original generated run IDs when sanitizing. Commit
reviewed evidence under `benchmarks/results/baseline-<run_id>/` together with
its provenance; the runner can write there directly using
`--output 'benchmarks/results/baseline-{run_id}'` after local smoke succeeds.

CLI `process_duration_ns` includes launcher/JVM startup and teardown/trace drain.
CLI `operation_duration_ns` stays unavailable because tracing-off has no internal
operation timer; `host_operation_duration_ns` describes traced registrar work
only. MCP `operation_duration_ns` measures the timed tools/call round trip,
excluding verification. Host-cold MCP process timing includes initialization,
discovery, verification and shutdown/drain. Warm MCP shares a process, so its
per-sample process boundary is unavailable. Reports leave those overhead
populations empty and name the gap.

Trace attribution requires one finalized, complete session with zero drops and
exactly one contiguous invocation per dispatched call, including verification
lookups. Any mismatch invalidates attribution for that session. Startup and
fixture setup are excluded from operation contributors. Host/native self times
use unions of same-clock child intervals; their inclusive totals must not be
added across nesting. HTTP duration minus native request duration is explicitly
an estimate of the service difference, never a self-time interval or one-way
network latency. Missing native snapshots, unavailable stages and incomplete
sessions remain gaps.

Extend the partial report above when additional configurations are collected,
linking their sanitized run directories and preserving hardware/version provenance
and unresolved gaps. Keep an unavailable platform visibly incomplete; fake-server
results cannot substitute for a device baseline.
