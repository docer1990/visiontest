# Recorded full-flow device investigation

There is no successful recorded full-flow pair on either target. No recording
overhead or median delta can be claimed. The [20 raw attempts](samples.jsonl)
include two warmup rounds and three measured rounds per platform, with mode
order alternating each round. Each platform has three successful measured plain
flows and three failed measured recorded flows. All five recorded warmup and
measured failures per platform remain in the raw JSONL. Each recorded attempt
has `videoValid: false`; `pairedMedianDeltaMs` is null on both platforms.

The [environment and checks](environment.json) contain the source commit, built
JAR hash, target and tool versions, full command arguments, two startup-failure
reports, a timeout report, and the original blocked attempt under
`previousAttempt`. [Traces](traces/) preserve the original host JSONL sessions
as gzip files; `tracePath` in each sample points to its archive. Harness
`processStartedNs`, `processFinishedNs`, and `elapsedMs` use a monotonic clock.
`phaseMs` is null because host tracing does not emit recording lifecycle
boundaries. Do not infer startup, execution or finalization durations from
overlapping host spans or from the flow report. The 2026-10-22 static-screen
feasibility numbers are not full-flow results.

## Actual device behavior

- Android 17 emulator `emulator-5554`, 1280x2856, screenrecord 1.4. Plain
  navigation flows input email, dismiss the keyboard with `press_back`, tap
  Continue, wait for Done, and observe the Done element. All recorded attempts
  fail before a step with `Android recording duration capability is unknown`.
  The emulator's `screenrecord --help` wraps `Set to 0` and `to remove the time
  limit.` onto separate lines; the exact-substring test in
  `AndroidRecordingCapabilities.kt:22` does not recognize that advertised
  unlimited capability. No Android video or resolution fallback was reached.
- iPhone 17 simulator `8CF7A9E3-89C0-49D8-B144-2BC6AF3B38FC`, iOS 26.5,
  display 402x874 points (1206x2622 pixels). Plain navigation flows reach and
  observe Done. Every recorded flow also completes its steps and observations,
  then fails validation in phase `finalize` with
  `Truncated recording box header`. The flow removes its unvalidated temporary
  video, so no successful `--record` artifact or decoded recorded transition
  can be claimed. This failure is raised by the container inspector at
  `RecordingContainerInspector.kt:105`; the exact box that triggers it has not
  been identified.

An independent `simctl recordVideo --codec=h264` probe around a successful
plain iOS flow produced [a native video](ios-native-transition-delayed.mov)
with 24 video samples. AVFoundation decoded its first, middle and last frames.
The first and last PNG hashes match, and visual inspection found only the
initial Start/Email/Continue screen, not Done, despite the plain flow reporting
Done. This probe is **not** a `--record` artifact or successful screen-transition
video. Its raw file and hashes are retained to distinguish structural success
from visible screen-transition evidence.

Startup-failure checks reset only the fixture, run a recorded flow, and inspect
the fixture afterward. Both platforms report the sole step as `skipped` with
`certainty: not_executed`; `done` remains absent and `continue` remains present.
Android fails duration preflight; iOS hits a 1 ms overall timeout during startup.
An iOS flow with 2500 ms overall timeout completed its first tap, timed out in
its wait, and preserved the step timeout as its primary error. Final video
validation still failed, so a saved timeout video cannot be verified. Android
cannot reach execution with recording until its duration parser accepts the
emulator's help text.

The focused packaged fake-recorder E2E test runs with ports 9008 and 9009 free,
not against the native servers. All 28 cases pass after updating the fake iOS
runtime identifier to the production parser format and accepting a precise
`EOF before stderr readiness` startup diagnostic. That suite does not replace
device verification. The servers launched for device testing were stopped
before running it; unrelated services were not touched.

## Reproduce and next decision

Build the JAR and both fixtures using [the fixture guide](../../fixtures/README.md).
Install the fixtures and run their native automation servers on the same isolated
targets selected by the recorder. The harness restarts only the fixture, waits
for `fixture_ready`, alternates modes by round, records every failure and uses
an explicit Done marker. Android needs `press_back` after email input to dismiss
its stylus keyboard overlay.

```bash
python3 benchmarks/recording_probe.py --platform ios \
  --target 8CF7A9E3-89C0-49D8-B144-2BC6AF3B38FC \
  --jar app/build/libs/visiontest.jar --output test-output/flow-recording-ios \
  --warmups 2 --rounds 3
python3 benchmarks/recording_probe.py --platform android \
  --target emulator-5554 --jar app/build/libs/visiontest.jar \
  --output test-output/flow-recording-android --warmups 2 --rounds 3
```

The harness leaves `videoValid: false` until someone decodes the actual
`--record` file and confirms the Start/Continue to Done transition. Retain
failed and ambiguous attempts. Only successful complete pairs with validated
video contribute to `summarize_samples`'s paired median. Production recording
issues need a separate approved code change before device verification and
phase-overhead measurement can finish. A preexisting iOS 27 fixture launch
failure and unavailable Android target from the prior attempt remain recorded
in `environment.json` instead of being erased.
