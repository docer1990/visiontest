# Recorded full-flow measurement, pending device verification

No paired full-flow performance result is claimed. [Raw samples](samples.jsonl)
is empty because neither available target could complete the repository
navigation fixture. [Environment](environment.json) records the source commit,
JAR hash, installed tools, attempted commands and blockers. The static-screen
feasibility numbers in the recording design are not full-flow benchmarks.

On 2026-10-22, `adb devices -l` returned no Android device. The sole booted
simulator was iPhone 17 iOS 27.0, UDID
`307E104B-04BD-4A49-8BD4-F5B88E12C6CE`. Its installed navigation fixture
launched but did not reach `fixture_ready`. The simulator log reported
`Application failed to launch: UIScene life cycle is required for apps built with this SDK`.
The compatible iOS 26.5 simulator was shutdown. The existing XCUITest server
on port 9009 was not stopped or replaced. No video, decoded screen transition,
startup-failure action check, or timeout-saved-video device check exists for
this attempt. The fixture launch was not a measured sample.

## Collect when isolated targets are available

Build the source JAR and native fixtures using [the fixture guide](../../fixtures/README.md).
Install the fixture, start the native automation server on the same isolated
device or booted simulator, and verify `fixture_ready`. The server must target
the recorder's selected serial or UDID. Then use a dedicated output directory:

```bash
python3 benchmarks/recording_probe.py --platform ios --target SIMULATOR_UDID \
  --jar app/build/libs/visiontest.jar --output test-output/flow-recording-ios \
  --warmups 2 --rounds 7
python3 benchmarks/recording_probe.py --platform android --target DEVICE_SERIAL \
  --jar app/build/libs/visiontest.jar --output test-output/flow-recording-android \
  --warmups 2 --rounds 7
```

The harness isolates the target before changing device state, restarts only the
fixture for each attempt, waits for `fixture_ready`, then enters email, taps
continue, waits for `done`, and checks the final element observation. It
alternates plain/record order by round. It writes each attempt immediately,
including setup and subprocess failures, without deleting failed samples.
Warmups are excluded from summaries. `elapsedMs` spans the flow subprocess;
fixture restart is excluded. No public flow report timing fields are added.

The harness marks every recorded video `videoValid: false` until a human checks
decoded frames. Do not treat a file size, report path or MP4 container as a
visible transition. Inspect the beginning and end using the platform video
viewer or `ffmpeg` frame extraction when available, confirm the email/Continue
screen changes to Done, and annotate each raw record with the actual
`videoValid` result and validation method. Use `summarize_samples` to recompute
the summary. Only successful complete pairs with a validated video contribute
to `pairedMedianDeltaMs`; retain failures and invalid recordings in JSONL.
Startup/execution/finalization phase timings require external lifecycle probes
or instrumented process timestamps; this harness does not infer them from
report fields. Record those separately before making a phase-overhead claim.

The current device block also prevents checking that startup failures dispatch
no steps and that an execution timeout still saves a valid video. Complete
these checks on both targets before calling the device verification finished.
