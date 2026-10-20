# Issue 56 flow measurements

Measured on 2026-10-19 against application commit `fa6e2df`. The same successful
sequence used five separate JVM invocations or one `flow` invocation.

| Mode | Samples | Median | Mean | Minimum | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| Separate commands | 7 | 3519.54 ms | 3732.76 ms | 3407.47 ms | 4966.50 ms |
| Flow | 7 | 2907.75 ms | 2926.13 ms | 2872.55 ms | 3037.07 ms |

The flow median was 611.80 ms lower, a 17.38% reduction (a 1.21x ratio of separate
to flow latency). All measured samples are retained, including the slower separate
sequence in round 7. Two warmup rounds per mode are excluded from these statistics.

## Environment

- macOS 27.0.1, Apple Silicon arm64.
- Corretto OpenJDK 17.0.18, Xcode 27.1 build 27A9275.
- iPhone 17 simulator, iOS 26.5, UDID `8CF7A9E3-89C0-49D8-B144-2BC6AF3B38FC`.
- Repository navigation fixture `com.example.visiontest.benchmark`, English.
- Native XCUITest server already running on localhost port 9009; tracing disabled.
- Full host/JVM output, source commit and JAR SHA-256 are in
  [environment.json](environment.json).

## Method

Before each sample, the harness terminates and launches only the fixture in its
navigation scenario and waits for its `fixture_ready` element. Setup is outside
the timed interval. Mode order alternates each round, separate then flow on even
rounds, flow then separate on odd rounds. There are two warmup rounds and seven
measured rounds, with both modes in every round.

Each timed sequence performs these operations in order:

1. Input `flow-benchmark@example.com` into the targeted `email` field.
2. Tap `continue`.
3. Wait for `done`.
4. Save a screenshot to a unique host path.
5. Retrieve the screen's interactive elements.

Every app-scoped command specifies the fixture bundle id. Separate mode launches
five new JVM processes. Flow mode submits the first four commands as steps and
requests `--observe interactive-elements`, with the outer fixture bundle scope.
Both modes use the same built JAR, server, fixture, operation arguments and default
timeouts. The harness checks action success, the expected element, the flow's
step statuses, the absolute screenshot path and PNG signature, and the final
interactive-element result containing `done`.

`perf_counter_ns` measures wall time from before the first process starts through
the last process exiting and output capture. Small host JSON assertions between
separate invocations are included. Each process's duration is also retained.
Fixture launch, readiness checks, server startup and image validation after the
sequence are excluded. No agent tool-call or model latency is included.

The first setup attempt used an already booted iOS 27 simulator. Its fixture
crashed before reaching `fixture_ready` because it does not adopt the scene
lifecycle required by that runtime. That attempt produced no measured samples.
The complete run above used iOS 26.5 with the unchanged fixture. There were no
retries or discarded samples in the completed run.

This is one local simulator and one short sequence. It measures the observed
benefit here, not a general speedup claim for Android, physical devices or other
workloads. Native interaction time remains a large part of the total.

## Reproduce

Build the JAR and fixture using the [benchmark setup](../../README.md), choose a
compatible iOS simulator, and install the fixture there. Start the native server
on that same simulator, for example from the repository root:

```bash
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,id=YOUR_SIMULATOR_UDID' \
  -parallel-testing-enabled NO \
  -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testRunAutomationServer
```

Once its health endpoint responds, run the harness from another terminal:

```bash
python3 benchmarks/results/flow-issue56/reproduce.py \
  --target YOUR_SIMULATOR_UDID \
  --output /tmp/visiontest-flow-measurement \
  --samples 7 --warmups 2
```

The harness aborts on any failed operation or assertion. Screenshots use a temporary
directory and are deleted after validation. It writes [raw samples](samples.jsonl),
[summary statistics](summary.json) and environment metadata to the output directory.
