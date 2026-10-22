# Sequential CLI flows

Use `flow` for a short sequence whose next actions are already known. Observe the
screen between sequences, and use an explicit element wait to verify navigation.
A successful tap only confirms the tap operation.

```bash
visiontest flow --platform ios --bundle-id com.example.app \
  --step 'tap_on_element --resource-id email --bundle-id com.example.app' \
  --step 'input_text "user@example.com" --bundle-id com.example.app' \
  --step 'tap_on_element --resource-id continue --bundle-id com.example.app' \
  --step 'wait_for_element --resource-id done --bundle-id com.example.app' \
  --observe interactive-elements \
  --on-failure screenshot \
  --json
```

The flow runs in one process and reuses its device components. It stops at the
first failed step. Completed actions remain applied. There is no rollback,
automatic retry, replay, resume, condition, loop, or parallel execution.

Add `--record` at the flow level to require one screen video for this invocation.
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
