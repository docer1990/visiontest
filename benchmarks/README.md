# Tests and performance benchmarks

Run every command below from the repository root. The Python harness measures
commands against dedicated Android/iOS fixtures through CLI or MCP. It does not
implement the planned 57D agent evaluation runner or measure model tokens, cost,
or image consumption. Current collection evidence and remaining gaps live in the
[HTTP baseline guide](../docs/benchmarks/http-baseline.md).

## Prerequisites

Use Python 3.11+ (standard library only), JDK 17+, and the repository's Gradle
wrapper. Android requires its SDK and `adb`; iOS requires macOS, Xcode command
line tools, and an installed simulator runtime. Configure `JAVA_HOME` and the
Android SDK as described in the [contributor guide](../CONTRIBUTING.md).
`jq` is optional for the report examples.

The device commands below install/reset the synthetic fixture. Use one isolated
Android device or one booted iOS simulator, with the corresponding automation
server running. Do not run collections, device tests, or other automation in
parallel on that target. Substitute your actual serial/UDID, simulator model,
and output directory wherever an example calls for them.

## Tests without a device

Run the complete Python harness suite. Device smoke is skipped unless explicitly
enabled; clearing the variables prevents accidental device access.

```bash
env -u VISIONTEST_BENCHMARK_SMOKE -u VISIONTEST_BENCHMARK_MANIFEST \
  PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s benchmarks/tests -v
```

For focused iteration, select one or more modules:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  benchmarks.tests.test_manifest benchmarks.tests.test_fixtures \
  benchmarks.tests.test_fixture_readiness -v
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  benchmarks.tests.test_assertions benchmarks.tests.test_execution \
  benchmarks.tests.test_mcp -v
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  benchmarks.tests.test_results benchmarks.tests.test_report \
  benchmarks.tests.test_traces -v
python3 -m benchmarks.run commands --help
```

Run JVM tests, including focused host/native timing tests and packaged-JAR tests:

```bash
./gradlew :app:test
./gradlew :automation-server:test
./gradlew :app:test --tests 'com.example.visiontest.performance.*'
./gradlew :automation-server:testDebugUnitTest --tests '*NativeTimingRecorderTest'
./gradlew :app:e2eTest --tests '*PerformanceTraceE2ETest'
```

Before a pull request, run the full repository gate:

```bash
./gradlew :app:test
./gradlew :app:e2eTest
./gradlew build
git diff --check
```

These tests validate behavior; their elapsed suite duration is not a device
performance baseline.

## Build native artifacts and fixtures

Build the host JAR and Android server APK pair, then install the pair on the
isolated Android target:

```bash
./gradlew :app:shadowJar
./gradlew :automation-server:assembleDebug :automation-server:assembleDebugAndroidTest
./gradlew :automation-server:installDebug :automation-server:installDebugAndroidTest
./gradlew :benchmark-fixture:assembleDebug
```

For iOS, change the destination model if necessary. The fixture's derived-data
path below matches the checked-in manifest.

```bash
xcodebuild build-for-testing \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17'
xcodebuild build \
  -project benchmarks/fixtures/ios/BenchmarkFixture.xcodeproj \
  -scheme BenchmarkFixture \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -derivedDataPath benchmarks/fixtures/ios/.derivedData
```

Native iOS unit tests require a simulator, but no separately running automation
server. Run them before collection, not concurrently with it:

```bash
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerTests
```

Use current server artifacts for native timings. Artifact discovery can select
an installed APK/bundle; verify the selected version and configuration using the
[installation guide](../docs/installation.md) and
[contributor guide](../CONTRIBUTING.md#build-from-source). Merely rebuilding source
does not establish which artifact the running server uses. Restart after an
upgrade. Older servers can still execute operations but leave native timing gaps.

## Configure the target and server

Keep private manifests and preliminary results under ignored `test-output/`:

```bash
mkdir -p test-output/benchmarks
cp benchmarks/manifests/android.json test-output/benchmarks/android.local.json
cp benchmarks/manifests/ios.json test-output/benchmarks/ios.local.json
adb devices
xcrun simctl list devices booted -j
```

Edit only the applicable local copy before running:

- Set `target` to the single online serial or booted simulator UDID. The harness
  checks isolation before every attempt.
- Verify `fixtureArtifact`, `cliPrefix`, and `mcpPrefix` point to the artifacts
  being tested. Preserve `fixtureIdentity` and the scenario setup commands.
- Pin `fixtureRevision` to `fixture-v2-settled` or the actual later tested revision.
  Rebuild fixtures after a revision change.
- Fill each `environment` entry (`build`, `nativeArtifactVersions`,
  `connectionType`, `targetOS`, `targetModel`, `JVM`, `hostOS`) with a `value`, or
  a specific `unavailableReason`. Include the commit and host hardware. Template
  placeholders are not provenance.
- Put necessary child launch variables in `processEnvironment`. This field is
  distinct from recorded environment metadata. Review local manifests before
  sharing them.

The runner installs and resets the fixture for each attempt. It polls
`fixture_ready`, then verifies scenario controls before starting the measured
operation. The appearance/disappearance fixtures settle after 750 ms; that delay
and readiness probes belong to setup timing. Old fixtures without the marker fail
setup. See the [fixture guide](fixtures/README.md) for exact setup semantics.

Start and inspect the selected server using the same built JAR as the manifests.
Run the Android pair or the iOS pair as applicable:

```bash
java -jar app/build/libs/visiontest.jar start_automation_server --platform android
java -jar app/build/libs/visiontest.jar automation_server_status --platform android

java -jar app/build/libs/visiontest.jar start_automation_server --platform ios
java -jar app/build/libs/visiontest.jar automation_server_status --platform ios
```

## Device smoke and short preflight

Run smoke for each available platform before collecting measurements:

```bash
VISIONTEST_BENCHMARK_SMOKE=1 \
VISIONTEST_BENCHMARK_MANIFEST=test-output/benchmarks/android.local.json \
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest benchmarks.tests.test_fixture_smoke -v

VISIONTEST_BENCHMARK_SMOKE=1 \
VISIONTEST_BENCHMARK_MANIFEST=test-output/benchmarks/ios.local.json \
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest benchmarks.tests.test_fixture_smoke -v
```

Then run one measured pair per case, without warmups, for a short preflight.
Set `benchmark_platform` to `android` or `ios`. Run sequentially and inspect any
failure before proceeding. This is a harness/preparation check, not a baseline.

```bash
benchmark_platform=android
(
  set -e
  for benchmark_mode in cli mcp; do
    PYTHONDONTWRITEBYTECODE=1 python3 -m benchmarks.run commands \
      --manifest "test-output/benchmarks/${benchmark_platform}.local.json" \
      --output "test-output/benchmarks/preflight-${benchmark_platform}-${benchmark_mode}-{run_id}" \
      --mode "$benchmark_mode" --lifecycle warm \
      --samples 1 --warmup 0 --tracing paired
  done
)
```

The checked-in manifests cover information, lookup, interaction, appearance,
disappearance, inspection, screenshot, and missing-target cases. There is no case
selection flag; use a separate local manifest with the desired `cases` subset for
focused diagnosis and retain that manifest with its results.

## Reproducible measurement matrix

After smoke and preflight pass, run all four mode/lifecycle combinations
sequentially for each available platform. Five warmup pairs and thirty measured
pairs mean 70 attempts per case, 35 in each tracing state. Pair order alternates
and both members receive independent fixture resets at the same revision.

```bash
benchmark_platform=android
(
  set -e
  for benchmark_mode in cli mcp; do
    for benchmark_lifecycle in warm host-cold; do
      PYTHONDONTWRITEBYTECODE=1 python3 -m benchmarks.run commands \
        --manifest "test-output/benchmarks/${benchmark_platform}.local.json" \
        --output "test-output/benchmarks/${benchmark_platform}-${benchmark_mode}-${benchmark_lifecycle}-{run_id}" \
        --mode "$benchmark_mode" --lifecycle "$benchmark_lifecycle" \
        --samples 30 --warmup 5 --tracing paired
    done
  done
)
```

Repeat with `benchmark_platform=ios` for eight configurations across both
platforms. The subshell stops at the first failed run;
retain its artifacts, diagnose the failure, and use a fresh output directory for
subsequent collection. Do not silently omit failed attempts from a report.

| Option | Meaning |
| --- | --- |
| `--mode cli` / `mcp` | Fresh CLI command process / MCP stdio tool invocation |
| `--lifecycle warm` | Native backend stays running; MCP reuses an initialized process per case and tracing state; CLI still starts a new JVM |
| `--lifecycle host-cold` | Fresh host process per attempt, including MCP initialization and shutdown |
| `--tracing paired` | Matched off/on trials for overhead comparison |
| `--tracing off` / `on` | Single-state trials; `--samples` counts individual measured attempts per case |
| `--samples N` / `--warmup N` | Positive measured count / nonnegative warmup count |
| `--output PATH` | Fresh directory; literal `{run_id}` is replaced by a generated UUID |

To collect only one tracing state, use the same command with `--tracing off` or
`--tracing on` and a fresh output path. Such runs do not supply paired overhead.
`--lifecycle native-cold` exists but is excluded from this baseline until explicit
`nativeLifecycle.stop` and `nativeLifecycle.start` argv arrays have been configured
and verified on the target. Do not infer a native restart from `host-cold`.

Runner exit codes are 0 when all requested assertions pass, 1 for sample/setup
failures, and 2 for invalid configuration. Exit 0 alone does not prove complete
trace attribution or sufficient hardware/version provenance.

## Trace an individual operation

The CLI root trace option precedes the subcommand:

```bash
java -jar app/build/libs/visiontest.jar \
  --trace-performance test-output/traces/android.jsonl \
  get_device_info --platform android --json
java -jar app/build/libs/visiontest.jar \
  --trace-performance test-output/traces/ios.jsonl \
  get_device_info --platform ios --json
```

For MCP, configure this environment variable on the server process launched by
your MCP client. This command starts the stdio server; the client must initialize
it and send tool calls to collect operation spans:

```bash
VISIONTEST_TRACE_PERFORMANCE=test-output/traces/mcp.jsonl \
  java -jar app/build/libs/visiontest.jar serve
```

CLI ignores that environment variable. MCP reads it once at startup. Give each
concurrent process a distinct trace path and shut down normally to allow trace
drain. The benchmark runner manages its own trace files and activation settings.
For trace fields, completeness requirements, and platform measurement limits,
see [local performance tracing](../docs/performance.md).

```bash
jq -r 'select(.stage != null) | [.operation, .stage, (.durationNs / 1000000), .outcome, .operationOutcome] | @tsv' \
  test-output/traces/android.jsonl
```

## Read and retain the results

Each fresh run directory contains:

| Artifact | Contents |
| --- | --- |
| `environment.json` | Run ID, manifest hash, cases, requested counts, fixture identity and supplied provenance |
| `samples.jsonl` | Every attempted sample, including warmups, failures, setup timing, pair membership and trace references |
| `summary.json` | Counts, outcomes, latency populations, contributors, paired overhead and measurement gaps |
| `traces/*.jsonl` | Per-attempt traces, or a shared traced warm MCP case session |
| `artifacts/<sample_sequence>/capture.png` | Separate screenshot artifact for each attempt; `screenshot_path` in samples is relative to the run directory |

Replace the example path with an actual generated directory:

```bash
benchmark_result=test-output/benchmarks/REPLACE_WITH_RUN_DIRECTORY
python3 -m json.tool "$benchmark_result/environment.json"
python3 -m json.tool "$benchmark_result/summary.json"
jq -c 'select(.status != "passed")' "$benchmark_result/samples.jsonl"
jq -c 'select(.tracing == "on") | {case_id, status, trace_complete, measurement_gaps}' \
  "$benchmark_result/samples.jsonl"
```

Compare measured groups separately by case, mode, lifecycle and tracing state.
Keep warmups separate. Read sample counts alongside median and nearest-rank p95;
empty populations have null estimates. Setup failures have no operation latency.
Reports distinguish all timed attempts from successful commands.

CLI process time includes JVM startup and teardown/trace drain; CLI operation-only
paired overhead is unavailable. MCP operation time is the tools/call round trip
without verification; persistent warm MCP has no per-sample process lifetime.
Traced registrar timing is a separate boundary. Missing native data, unsupported
stages, or incomplete traces are gaps, not zero time. Inclusive parent/child spans
must not be summed; host and native clocks must not be subtracted. HTTP duration
minus native request duration is an estimate, not one-way network latency.

Retain failed/uncertain attempts and do not replay timed-out operations to repair
an existing sample. A screenshot reference does not establish that the file
exists or passed validation. Preserve full output directories, smoke diagnostics,
local manifests and build provenance; review identifiers and screenshots before
publishing. The [baseline guide](../docs/benchmarks/http-baseline.md) describes the
evidence destination and current collection status. The
[behavioral contract](../docs/agentico/specs/performance-benchmarks.md) defines
schemas, assertions, attribution and overhead eligibility.

After collection, stop the server for the selected platform:

```bash
java -jar app/build/libs/visiontest.jar stop_automation_server --platform android
java -jar app/build/libs/visiontest.jar stop_automation_server --platform ios
```
