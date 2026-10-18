# Native benchmark fixtures

These apps contain synthetic screens only. They never read or store user data.
Android uses platform Views and iOS uses UIKit. Both use
`com.example.visiontest.benchmark`; startup resets the selected scenario and
language (`en` or `it`). The `appear` and `disappear` scenarios use a fixed
750 ms fixture delay. Keep that delay outside command CPU comparisons.

Build from the repository root:

```sh
./gradlew :benchmark-fixture:assembleDebug
xcodebuild build -project benchmarks/fixtures/ios/BenchmarkFixture.xcodeproj \
  -scheme BenchmarkFixture -destination 'platform=iOS Simulator,name=iPhone 17' \
  -derivedDataPath benchmarks/fixtures/ios/.derivedData
```

The manifests pin the fixture artifact and identity. Copy the platform manifest
to a private file, replace `target` with the one isolated device serial or
simulator UDID, then build the VisionTest JAR (`./gradlew :app:shadowJar`).
The runner refuses to proceed unless that is the only active Android device or
booted iOS simulator, installs the dedicated fixture, and verifies its package
identity before terminating or launching it. Android force-stop and fixture
launch must exit 0. `simctl terminate` may exit 3 only when the fixture was not
running; the manifest allowlist applies to that command alone. Every other setup
failure is recorded as `setup_failed` and skips the measured operation.

After launch, the runner polls the stable `fixture_ready` label before inspecting
any scenario controls. Fixtures add that label after their initial controls;
the delayed scenarios add it in their 750 ms completion callback, after adding
`done` or removing `loading`. This keeps readiness lookups away from controls
during their transition. Once the marker is present, setup checks `start` and
the selected scenario's ready state. Appearance
must have produced `done`; disappearance must have removed `loading`. Navigation,
missing, ambiguous, and error scenarios are checked against their corresponding
controls. These probes use the configured `cliPrefix`, disable performance
tracing, and share one readiness budget equal to the case's `timeoutMs`, with
at most 100 ms between observations. Invalid results, backend/process failures,
and deadline expiry fail setup. Readiness and the 750 ms transition delay are
included only in setup timing, before the measured command starts.
Rebuild the native apps for `fixture-v2-settled` and update private manifest
revisions before collection. Older fixture builds lack the marker and time out
during readiness.

Manifest setup commands are argv arrays. `{target}`, `{fixtureArtifact}`,
`{fixtureIdentity}`, `{scenario}`, and `{language}` are supported only as whole
argv values. Shell commands and embedded token substitution are rejected.
The current fixture setup uses the existing `adb` and `xcrun simctl` tools.

Device smoke tests are opt-in. Set `VISIONTEST_BENCHMARK_SMOKE=1` and
`VISIONTEST_BENCHMARK_MANIFEST` to the edited manifest path, then run
`python3 -m unittest benchmarks.tests.test_fixture_smoke -v`. The selected
VisionTest automation server must already be running. These checks cover
idempotent reset, mutually exclusive done/error states, delayed transitions,
missing targets, and duplicate visible labels using VisionTest inspection
commands. They do not run during the default Python suite.
