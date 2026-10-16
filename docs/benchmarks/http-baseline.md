# HTTP command baseline: collection pending

The command harness and trace reports are implemented. Real Android/iOS baseline
collection is incomplete: no Android target is attached on the implementation
host, and the available iOS simulator has not completed fixture smoke or the
requested benchmark matrix. There are no measured baseline counts, latency
results, overhead estimates, or speedup claims in this document. Synthetic
protocol tests and backend-unavailable trace smoke checks establish harness
behavior only. Issue #57 remains open for command evidence and agent evaluation.

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
- `artifacts/`: screenshot assertion files where applicable, containing only the
  synthetic fixture. These are assertion artifacts, not proof of image consumption.

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

After actual collection, replace this pending status with platform/configuration
counts, hardware/version provenance, measured contributors and unresolved gaps,
linking the sanitized run directories. Keep an unavailable platform visibly
incomplete; fake-server results cannot substitute for a device baseline.
