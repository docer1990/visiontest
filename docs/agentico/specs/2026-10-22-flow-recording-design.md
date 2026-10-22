# Required screen recording for CLI flows

Approved on 2026-10-22. Decisions are TD-003, TD-005, TD-016, TD-018 and TD-019.
This extends [sequential flows](2026-10-19-flow-design.md).

## Activation and scope

`visiontest flow --platform android|ios --record` requests one screen recording
for that invocation. It works with repeated `--step`, `--file` and `--stdin`.
`--record` is a flow-level flag, not a step. Existing flow validation runs before
device discovery, recorder capability checks, process creation or file creation.
Invalid later steps still prevent all device access.

Without `--record`, preserve existing execution, report fields and artifact
behavior. Do not initialize a recorder, inspect its capabilities or touch video
paths. Recording uses Android devices/emulators and iOS simulators supported by
the existing host tools. Capture the display without audio. iOS app scope does
not crop or otherwise restrict the video.

Use Android `screenrecord` through ADB and iOS `xcrun simctl io <UDID> recordVideo`
with H.264. Allow the Android command's existing resolution fallback. A diagnostic
line containing `ERROR` does not by itself establish failure if the recorder
successfully initializes after its own fallback. Add no native JSON-RPC methods,
MCP tools, standalone recording command or video-transcoding dependency.

## Ownership and architecture

`FlowCommand` validates the flag alongside the complete prepared sequence.
`FlowExecutor` coordinates the optional recording lifecycle around existing shared
step operations and observations. A recording session exposes bounded startup,
asynchronous terminal status and idempotent stop/finalize operations. Platform
adapters own tool invocation, readiness detection and artifact transport.

Resolve the recording target once through existing device selection. Use its
explicit serial or UDID for every recording, inspection, stop and transfer command.
Do not rediscover or switch recording targets during a flow. The selected target
must match the device served by the flow's automation backend; cover that setup
in device tests and document the existing single-target assumption.

An invocation owns its recorder handles, remote PID and temporary paths. Cleanup
must never signal unrelated recorder processes or remove unrelated files. Drain
process output while it runs, bound retained diagnostics and keep subprocess
output out of CLI stdout. Do not apply the existing short, wait-until-exit process
executor to a long-running recorder.

## Startup and readiness

Create the invocation's artifact destination and check that it can be written
before executing a step. Recorder setup is part of execution, so failures here
produce the normal flow report in JSON mode. A missing device retains exit 4.
Other recorder initialization failures, including an unsupported recording budget,
exit 1 with a specific error. Syntax and argument failures remain preflight errors
with exit 2 and empty stdout.

Startup has a maximum of 10000 ms and is also capped by the remaining overall
flow budget. That deadline includes recording-specific discovery, capability
inspection, destination preparation and readiness. It is an upper bound on
waiting, not a fixed delay.

- iOS readiness requires the documented `Recording started` stderr signal and a
  still-running process. The signal means the first frame has been processed.
- Android readiness requires a still-running recorder and an initialized MP4
  container with valid box structure through its `mdat` header. Do not accept
  file existence, file size alone or a substring match in arbitrary bytes. Do
  not wait for frame payloads to become visible on disk. Check readiness only
  during startup, at bounded intervals, with each read under the startup deadline.

If startup fails, execute no step and report all steps as skipped. Final
observations and failure evidence are skipped because no flow step began. Clean
up any partially started recorder within the finalization budget. A timeout must
not leave startup work running after the flow has returned.

## Execution and failures

Start the first step only after platform-specific readiness succeeds. Monitor
process termination asynchronously while steps, final observations and requested
failure evidence run. Do not add a device round trip or sleep before each step.
Monitor termination status even when the native command exits with code 0; any
exit before intentional stop is premature.

On premature exit, stop dispatching further steps and cancel the active flow
operation. Preserve the existing action-dispatch evidence. An action already
sent without a validated response has an unknown effect and is never replayed.
Completed steps stay completed, and unstarted steps are skipped. Recorder failure
does not change a validated operation result into an uncertain action.

The primary error is the first failure committed to the flow report. A later
recording failure cannot replace an existing step or observation error or its
exit code. If recorder failure is primary, exit 1. Requested failure evidence
can still run under its own budget after execution fails, even if recording has
already stopped. Evidence failure does not replace the primary error.

When steps succeed, keep recording through final observations. When execution
fails, keep a healthy recorder running through requested failure evidence.
Do not collect failure evidence a second time if finalization itself fails.

## Deadlines, duration and finalization

Recording startup, steps and final observations share the existing `--timeout`.
Failure evidence retains its existing separately bounded `--evidence-timeout`.
Use one additional total finalization budget of 30000 ms for stop, process exit,
Android transfer, file validation and cleanup. It remains available after the
execution deadline expires. It is not an unconditional additional wait.

Inspect Android recorder capabilities before launching the recording. Use an
unlimited duration only when the installed tool advertises support. Otherwise,
require its supported duration to cover the full configured execution budget,
the evidence budget when evidence was requested, and the finalization budget.
Round the sum up to whole seconds. Reject unsupported or unestablished duration
capability before the first step, with the supported and requested bounds in the
error when known. Do not silently truncate the video, restart the recorder or
split the flow into recording segments.

Stop normally with the platform's supported interrupt signal and wait for file
finalization. Signal only the owned recorder; stopping the ADB client alone is
not proof that Android's remote recorder stopped. Bound all waits and transfers.
Use forced termination only as bounded cleanup after graceful stop fails and
report recording failure. Clean up within cancellation-resistant finalization
when a coroutine is cancelled. JVM shutdown should attempt bounded cleanup of
owned resources, but abrupt process or host termination cannot guarantee a saved
video or a final JSON report.

## Artifacts and report

Share one unique `flow-artifacts/<invocation>/` directory with the invocation's
observations. Save Android video as `recording.mp4` and iOS video as `recording.mov`.
Use temporary names until a file is finalized and structurally validated. The
validated container must contain a video track with at least one nonempty sample
whose offsets and sizes fall within media data. A metadata track or nonempty
file alone is insufficient. Validation is bounded structural inspection, not a
full decode of every frame or a guarantee that protected content is visible.

Return only an absolute host path to a successfully validated artifact. Video
bytes never enter the JSON report. A valid partial recording can be retained
after premature recorder exit, but its recording status remains failed. Remove
invocation-owned temporary files on normal cleanup. If transfer fails, retain
the remote artifact when possible and identify it in the diagnostic for recovery.

When `--record` is supplied, add a `recording` object to the schema-version-1
report with these fields. Omit the entire field when the flag is absent.

| Field | Values |
| --- | --- |
| `status` | `succeeded` or `failed` |
| `phase` | `start`, `record` or `finalize` for failure; null for success |
| `path` | Absolute host path to a validated artifact, or null |
| `error` | Existing `{type, message}` error shape, or null |

In text mode, print the recording status, any error and the saved path. In JSON
mode, emit exactly one complete report object once execution has begun, including
startup failures. Main diagnostics go to stderr. A recording failure after all
steps succeed makes the overall flow fail without changing their statuses. If a
step already failed, show both errors while retaining the step's primary error
and exit code. Successful video preservation does not turn failed steps into a
successful flow.

## Verification

Use executable BDD scenarios for public flow behavior and focused TDD for process
ownership, container inspection, timing and cancellation. Cover both platforms,
all input sources, disabled behavior, invalid later steps, startup timeout,
premature zero and nonzero exits, dispatched-action uncertainty, evidence,
finalization/transfer failure, zero-sample files, duration boundaries and cleanup.
Test stdout framing and text output using the packaged CLI and fake executables.
Use synchronization gates and virtual clocks for ordering rather than timing
thresholds in unit tests.

Run `./gradlew :app:test`, `./gradlew :app:e2eTest`, `./gradlew build` and
`git diff --check` before delivery. Run device smoke tests and paired recordings
on Android and iOS Simulator. Confirm that the saved video contains the expected
screen transition, not only a successful subprocess exit. Compare identical
flows with and without recording, separating startup, flow execution and
finalization/transfer timings. Retain failed samples and environment information.

Update the flow guide, JSON report documentation, README, CLI behavioral contract
and distributed agent instructions. Document platform readiness differences,
Android duration restrictions, failure precedence and added finalization budget.

## Preliminary feasibility evidence

Local probes on 2026-10-22 used macOS 27.0.1 and Xcode 27.1. They recorded a static
display for about two seconds without running a flow or changing the UI.

| Target | Samples | Startup observation | Finalization | Transfer |
| --- | ---: | ---: | ---: | ---: |
| iPhone 17, iOS 27 simulator, H.264 | 3 | 130.59 ms median, first-frame signal | 15.31 ms median | Direct host output |
| Pixel 9 Pro, Android 17/API 37.1 emulator | 1 | 208.27 ms, initialized container | 237.81 ms | 30.64 ms |

Android `screenrecord` 1.4 fell back from 1280x2856 to 720x1280 after codec
configuration failed. The final MP4 had one AVC video sample. The first Android
probe timed out after ten seconds while waiting for frame payloads. A subsequent
probe initially misclassified finalized data because metadata samples preceded
the video sample; checking the video track's chunk offset established its presence.
The API 36.1 emulator could not start because its configured system-image directory
lacked `system.img`; the API 37.1 image was used instead.

These measurements establish feasibility only. They are neither full-flow
performance results nor evidence for physical Android devices or older recorders.
