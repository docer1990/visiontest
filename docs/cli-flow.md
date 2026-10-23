# Sequential CLI flows

Batch known actions with `flow --json`. During exploration, omit `--record` and
inspect structured UI data where a decision needs new information. End with an
explicit element wait when the outcome selector is known. A successful tap only
confirms the tap operation.

The distributed agent skill requires `--record` only for a complete, defined
scenario that still needs testing: its starting state, action sequence, selectors,
data and assertions must be established. Use one recording for that scenario,
including on its first execution, without a mandatory rehearsal. Do not create
a video per exploratory segment. Report user opt-out or concrete recorder, flow
or budget limitations before omitting recording from a ready test.

Before every recorded flow, terminate the app, inspect the result, relaunch it and
inspect the launch result. This applies even when exploration left the app on the
right screen, and before consecutive recorded tests. Keep the automation server
running. Unrecorded exploratory flows do not require a restart.

This example assumes the app opens on the form and the scenario ends at `done`.
First terminate on the established simulator:

```bash
xcrun simctl terminate "UDID" "com.example.app"
```

Continue only after successful termination or a specific already-stopped diagnostic.
Other failures block the test. Relaunch separately:

```bash
visiontest launch_app com.example.app -p ios
```

Inspect the launch result, including diagnostics on exit 0. Only on success execute:

```bash
visiontest flow --platform ios --record --bundle-id com.example.app \
  --step 'tap_on_element --resource-id email --bundle-id com.example.app' \
  --step 'input_text "user@example.com" --bundle-id com.example.app' \
  --step 'tap_on_element --resource-id continue --bundle-id com.example.app' \
  --step 'wait_for_element --resource-id done --bundle-id com.example.app --timeout 10000' \
  --observe interactive-elements --compact \
  --on-failure interactive-elements \
  --json
```

The flow runs in one process and reuses its device components. It stops at the
first failed step. Completed actions remain applied. There is no rollback,
automatic retry, replay, resume, condition, loop, or parallel execution.

Before recording, review the final sequence and read any flow file after its last
edit. Preserve requested assertions and choose explicit assertion timeouts from
requirements and evidence; a longer overall budget does not extend an element wait.
Do not silently lose a previously established timeout or remove a required check
while converting exploration into a test. Resolve contradictory prerequisites:
an unavailable prerequisite blocks the test, while a failed assertion with valid
prerequisites fails it. Do not change the expected outcome to match the observation.

For an exploratory swipe followed by a known unique selector tap, use an
unrecorded flow without a fixed delay. The tap waits for actionability:

```bash
visiontest flow -p ios --json \
  --step 'swipe_direction up --distance medium' \
  --step 'tap_on_element --bundle-id com.example.app --text "Known item"'
```

`--bundle-id` is command-specific, not a global iOS option; `swipe_direction`
does not accept it. Check options against the exact command before execution.
If the target is still unknown,
end after the swipe and request `--observe interactive-elements --compact` with
the outer iOS `--bundle-id`, then inspect the result before choosing the next action.
A known navigation outcome still needs an explicit wait. Do not chain standalone
actions with `; sleep ...;`: later actions would run even if the first failed.
Do not insert `sleep 0` as a placeholder or separator either.

The example uses `--record` at the flow level to require one screen video for this invocation.
It works with repeated `--step`, `--file`, and `--stdin`; do not put it inside a
step. Recording captures the whole display without audio, even when iOS element
inspection uses `--bundle-id`. Without `--record`, the flow does not inspect
recorder capabilities, touch video paths, or add a recording report.

## Input

Supply exactly one of repeated `--step 'COMMAND ...'`, `--file PATH`, or `--stdin`.
Each source uses the same grammar, with one command per line. Empty input and
comment-only input are errors. Blank lines and lines whose first non-whitespace
character is `#` are ignored. A `#` elsewhere is an ordinary character.

Single or double quotes group an argument, including an empty argument (`""`).
A backslash escapes the following character outside single quotes. Inside single
quotes every character is literal. Unclosed quotes and a trailing escape are
errors. Quotes cannot span lines. Variables, `~`, command substitutions, wildcards
and `@file` are never expanded. No shell executes a step.

The outer shell processes the `--step` argument before VisionTest receives it.
Use single quotes around a step to protect dollar signs and inner double quotes,
or use a file. The normal `--` separator allows positional text beginning with
a dash, for example `--step 'input_text -- --help'`.

All command names, arguments, platform restrictions, and observation options are
validated before creating device components or contacting a backend. An invalid
last step prevents the first step from running. Element presence and device state
are checked during execution.

## Supported steps

| Platforms | Commands |
| --- | --- |
| Android and iOS | `tap_by_coordinates`, `tap_on_element`, `input_text`, `long_press`, `double_tap`, `swipe`, `swipe_direction`, `swipe_on_element`, `press_home`, `wait_for_element`, `screenshot` |
| Android | `press_back`, `press_key`, `clear_text` |
| iOS | `dismiss_keyboard`, `handle_alert` |

Operation arguments are the same as the corresponding CLI command. Specify
`--platform` once on `flow`; do not put platform, help, or `--json` options inside
steps. An unsupported platform operation exits 5. Other commands, including
server setup, app management, inspection commands and nested flows, are rejected.
Use observation options for screen inspection.

`wait_for_element` requires a selector. It waits for appearance by default and
disappearance with `--gone`. Missing or invalid backend fields never count as
absence. `screenshot` accepts `--output` and reports the saved absolute host path.
Without `--output`, flow screenshots use unique names under `screenshots/`.
Invalid host-path syntax is rejected before any step runs. Filesystem existence,
permissions and writability are checked when the screenshot executes.

## Time budgets

All timeout values are milliseconds.

| Option | Default | Applies to |
| --- | ---: | --- |
| `--step-timeout` | 35000 | Each step, including readiness checks and response processing |
| `--timeout` | 120000 | Execution of the whole flow, including final observations |
| `--evidence-timeout` | 5000 | All requested failure evidence together, after execution fails |

Step and overall timeouts accept 1 through 3600000; evidence accepts 1 through
30000. Existing operation-specific timeouts retain their limits and defaults.
For example, the `--timeout` inside a `wait_for_element` step still has a maximum
of 30000. The earliest applicable deadline wins. The element-wait budget begins after
readiness checks, matching the individual command. Native selector operations
retain their existing HTTP grace period; step and overall deadlines cap the total
time, including readiness and transport. Parsing is outside the execution budget.
Failure evidence adds its separately bounded budget to execution time.

Recording startup shares the overall `--timeout` and cannot exceed 10000 ms.
This is a ceiling, not a fixed delay. iOS waits for `simctl recordVideo` to
confirm its first frame; Android waits for a live `screenrecord` process and
an initialized MP4 container, not a frame payload. The first step starts only
after readiness. Android recorder duration must support the configured overall
budget plus requested evidence time and 30000 ms of finalization, rounded up to
seconds, unless the installed recorder advertises unlimited duration. Unsupported
or unestablished capability fails before dispatching a step. One additional
*total* 30000 ms finalization budget covers stop, exit, Android transfer,
validation and cleanup, including when execution timed out. It is not an
unconditional sleep.

A deadline can interrupt response collection after the server has received an
action. It cannot undo device actions. Such an action has an uncertain outcome.

## Observations

Repeat `--observe` to request final observations after successful steps, or
`--on-failure` to request evidence after an execution failure. Each accepts
`screenshot`, `hierarchy`, or `interactive-elements`. No observation is automatic
unless requested; a full hierarchy is not collected after every action.

Use outer `--compact` for `compact-v1` interactive-element results. Optional
`--type TYPE`, `--region x,y,width,height`, and `--include-bounds` require it.
These options apply to both final observations and failure evidence, and require
at least one `--observe interactive-elements` or `--on-failure interactive-elements`.
Invalid combinations fail preflight before device access. See the
[compact schema and filtering rules](cli-json.md#compact-interactive-elements).
Other observations and step results keep their existing formats. Use `--json`
for a structured flow report; `--compact` alone does not select report formatting.
End known navigation sequences with an explicit `wait_for_element` step before
requesting the final observation.

The agent skill uses structured results as functional evidence and restricts
screenshots to observed app bugs or assertions that CLI queries cannot resolve.
Do not add screenshots after each step or to confirm a successful presence wait.
For a UI bug, capture the failing state before recovery when possible. For an
unresolved assertion, identify what the structured data cannot establish before
capturing and inspecting an image. These rules apply to screenshot steps and
both observation options.
Prefer compact interactive elements for failure diagnostics, with the outer iOS
`--bundle-id` scope. Do not enable `--on-failure screenshot` as a generic default;
justify it before execution for a known UI bug reproduction or a specific assertion
requiring image evidence. A functional timeout alone is insufficient.

Screenshots and hierarchies are saved on the host and return absolute artifact
paths. Interactive elements are returned as structured data. Reports never embed
image base64. Each observation reports its own result or error.

On iOS, outer `--bundle-id` scopes hierarchy and interactive-element observations,
including failure evidence. Without it, these queries target SpringBoard, matching
the individual inspection commands. It does not change step arguments; provide
`--bundle-id` inside each step that needs an app scope. Android rejects this option.
Screenshots always capture the display.

A failed final observation makes the flow unsuccessful without changing completed
step statuses. Evidence failures are reported separately and preserve the original
failure and exit code. Evidence collection stops when its total budget expires.

Recording continues through final observations on success and requested failure
evidence on execution failure. It is required: a recorder or video-validation
failure makes successful steps fail overall. The first failure remains primary.
A later recording failure does not replace a step or observation error, its exit
code, or completed step statuses. Startup failure skips all steps, final
observations, and evidence. Recording resolves one target using existing
selection rules and pins its serial or UDID. Configure the automation server
for that same target and isolate it from other active targets. The backend does
not supply identity for a runtime match check. Recording does not switch devices
or boot a shutdown simulator.

When closing the app after the final requested flow, wait for the flow process
to return, inspect step and recording results, and capture any necessary bug
evidence first. Then use a native command outside flow, such as
`xcrun simctl terminate "UDID" "com.example.app"` on the established simulator.
Do not terminate during video finalization. Wait for it and required failure
evidence before restarting for the next recorded test; exploratory flows may reuse state.
Inspect termination's exit status and diagnostics and report cleanup failures
separately from test outcomes.

Explicit reruns follow the same mandatory restart procedure as every recorded test.
Include navigation from the fresh launch to the test state in the recorded flow;
do not record only the tail from a screen reached during exploration. Restart does
not clear persisted app data. Reuse established selectors and assertions; selector actions already wait for
actionability. Include an initial known readiness wait in the flow if needed.
Restart alone requires neither sleeps nor rediscovery. An unexpected screen may
invalidate the known path and require unrecorded exploration, followed by another
restart before recording. A requested rerun
is a new test even when the previous execution passed.

## Output and recovery

Default text summarizes the number of completed and skipped steps and identifies
the failure, followed by saved step artifact paths. Every requested observation is
also printed under a `Final KIND` or `Evidence KIND` label with a compact JSON
record containing its kind, status, result and error. This includes skipped and
failed records, complete validated interactive-element data, and absolute artifact
paths. Observation artifact paths appear in their records once.
`--json` emits one complete JSON object followed by a newline, without
JSON-RPC framing. See [the JSON contract](cli-json.md) for fields and examples.
With `--record`, text output also reports recording status, any error and the
saved video path. Video is saved under the invocation's `flow-artifacts/`
directory as Android `recording.mp4` or iOS `recording.mov`. Only a finalized,
structurally validated video with a nonempty video sample gets an absolute host
path in the report; partial valid video may survive a failed recording. Validation
does not guarantee that protected content is visible. Video bytes are never
embedded in the report.

Preflight failures produce stderr diagnostics and empty stdout. Once execution
begins, JSON mode emits a report even when a step throws. Diagnostics stay on
stderr. This differs from individual commands, which leave stdout empty on thrown
failures.

Exit 0 means every step and every requested final observation succeeded. A returned
operation failure exits 1. Thrown failures retain the existing mapping: 1 generic,
2 invalid arguments, 3 unavailable automation server, 4 missing device, and
5 unsupported platform. Failure evidence cannot change that exit code.
Missing recording target exits 4; other recorder startup, premature exit,
finalization and validation failures exit 1 when they are primary.

Step identifiers are positional (`step-1`, `step-2`, and so on) and remain stable
within the submitted flow, including skipped steps. A validated backend failure is
a confirmed failure; it does not promise that an action had no partial effect.
When an action may have been dispatched and no valid result establishes its
outcome, the report marks its effect as unknown. Readiness failures before dispatch
are distinguishable from these uncertain outcomes. Default text output also warns
that the action effect is unknown and advises inspection before retrying. Inspect the current UI before
deciding how to recover; never assume that an uncertain action did not happen.

## Measured process savings

A local iPhone 17 / iOS 26.5 simulator run compared the same form sequence, wait,
screenshot and final element observation. Seven samples per mode after two warmup
rounds gave medians of 3519.54 ms for five separate invocations and 2907.75 ms for
one flow, a 17.38% reduction. This result is specific to that environment and
sequence. See [the measurements and reproduction method](../benchmarks/results/flow-issue56/README.md)
for raw samples, checks, environment and limits.
