# Sequential CLI flows

Approved design for issue #56. Decisions: TD-003, TD-006, TD-016.

## Input and preparation

`flow --platform android|ios` accepts exactly one input source: repeated `--step`,
`--file PATH`, or `--stdin`. Each entry is one command. Blank lines and lines whose
first non-whitespace character is `#` are ignored. Single and double quotes group
arguments, including empty arguments. Backslash escapes the next character outside
single quotes. Unclosed quotes and dangling escapes are usage errors. There are no
inline comments, multiline continuations, shell execution, or expansions.

Validate the whole sequence and observation configuration before creating device
components. Reuse command argument definitions and validation. Supported steps are
`tap_by_coordinates`, `tap_on_element`, `input_text`, `long_press`, `double_tap`,
`swipe`, `swipe_direction`, `swipe_on_element`, `press_home`, `wait_for_element`,
and `screenshot`; Android additionally supports `press_back`, `press_key`, and
`clear_text`; iOS additionally supports `dismiss_keyboard` and `handle_alert`.
Reject nested flows, other commands, step-level platform/output-mode options,
and unsupported platform combinations before any backend access. Validate screenshot
output host-path syntax during preparation; filesystem existence, permissions and
writability are checked only when the screenshot executes.

## Execution and outcomes

Create one ComponentHolder and execute sequentially using shared registrar
operations. Structured internal results expose operation success, error and saved
paths without interpreting English text. Existing standalone CLI and MCP behavior
remains compatible. No new native endpoints or MCP tools are introduced.

Stop at the first failed operation or exception. `success:false` and valid RPC
errors fail the flow with exit 1. Appearance requires `found:true`; disappearance
requires a valid `found:false` response. Invalid responses never mean absence.
Thrown errors preserve mapped exit codes 1 through 5. No rollback or replay occurs.

Each submitted step receives a stable position-based id (`step-1`, etc.), including
skipped steps. Results identify success, confirmed failure, unknown effect, or
skipped execution. A transport error after possible action dispatch has unknown
effect unless stronger evidence exists. A validated operation failure confirms
failure but does not promise the action had no partial effect.

## Deadlines

`--step-timeout` defaults to 35000 ms and `--timeout` to 120000 ms. Both must be
positive bounded integers. Existing operation-specific timeouts retain their
validation and defaults. The closest applicable operation, step, or overall deadline wins. Host element
wait budgets begin at polling after readiness; native selector operations retain
their existing HTTP grace periods. Step and overall budgets cap the total time.
The overall execution budget includes component creation and final observations,
but excludes input parsing. HTTP connect/read operations respect remaining budgets.

## Observations and reports

Repeatable `--on-failure` and `--observe` accept `screenshot`, `hierarchy`, or
`interactive-elements`. No observations run unless requested. Failure evidence
uses a separate total budget, `--evidence-timeout` (default 5000 ms). It preserves
the original action error and exit code on observation failure. Final observations
run after successful steps and use the remaining execution budget; their failure
makes the flow unsuccessful without changing completed step statuses.

Screenshot and hierarchy artifacts have absolute host paths. No image base64 is
included. Interactive elements use the validated inspection schema. Each observation
reports its own status, result and error. The outer iOS `--bundle-id` scopes
hierarchy and element observations, including evidence, through existing registrar
arguments. It does not supply step scope. Without it, queries retain the existing
SpringBoard default. Android rejects this flag during preflight. Preflight errors write only stderr.
Once execution starts, JSON mode writes exactly one complete report object and a
newline, even on failure. Diagnostics go to stderr. The report includes schema
version, platform, overall success and exit code, steps, failed-step id, skipped
ids, final observations and failure evidence. Default output summarizes execution
and warns when an action effect is unknown, with advice to inspect the UI before
retrying.

## Verification and delivery

Use BDD scenarios for observable flow behavior, backed by JUnit tests and focused
TDD for parsing, result contracts and HTTP deadlines. Cover both platforms, all
input modes, invalid later steps, returned failures, malformed responses, uncertain
dispatch, timeout, absence, evidence errors and final observation failures.
Run `:app:test`, `:app:e2eTest`, and `build` without changing quality baselines.
Update CLI contracts, JSON schema documentation, README and distributed guidance.
Record representative separate-process and flow timings with environment and
methodology, using available benchmark fixtures; identify any unavailable device
measurements explicitly.
