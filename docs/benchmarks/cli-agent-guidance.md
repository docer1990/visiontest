# CLI agent guidance trials

Issue #81, 2026-10-23. These are simulated command-selection trials, not app E2E
runs or a Haiku benchmark. Two fresh agents of the session's inherited model read
only the distributed instruction body, one before and one after the revision.
The user's reported Haiku observations motivated the scenarios; we did not execute
Haiku or verify a particular Haiku model version.

## Scenarios and observations

Both agents received the same four core scenarios: delayed Android Pay followed
by Receipt with evidence and a 30-second budget; iOS clean state and a deep link
with two query parameters; known Continue/Done flow with evidence and returned
operation/recording failures; and CLI INFO noise. The updated trial additionally
supplied the Android equivalent of the deep link to check two-shell quoting.

| Scenario | Previous instructions | Updated instructions |
| --- | --- | --- |
| Delayed Pay → Receipt | Selector tap + wait, `--observe screenshot`; no recording for strict budget | Selector tap + wait, structured evidence; recording omission explicitly reported for strict budget |
| iOS reset/deep link | Correct simctl commands inferred; noted lack of dedicated reference | Used explicit UDID and quoted URL; scoped outcome wait; project reinstall required; no complete-reset claim |
| Continue → Done | `--observe screenshot`; “Use screenshot evidence unless the user specifically needs temporal/video evidence” | `flow --record --json`; “No screenshot is needed absent a visual assertion” |
| Returned failure / failed video | Distinguished functional success from failed recording; no replay | Same; explicitly reports overall flow failure and retains validated path if available |
| INFO noise | Separated streams, avoided blind filtering; noted missing logger guidance | Separated streams, retained status/errors, no `grep -v INFO`; understood default suppression |

For these simulated known-selector scenarios, both agents proposed zero help
calls, selector-discovery calls and retries. The baseline proposed two screenshots
for functional evidence; the updated agent proposed zero. These are counts of
proposed commands, not executed calls. Both used built-in waits, so this trial did
not reproduce the user's reported sleep behavior. The updated skill makes that
rule explicit, but robustness across weaker models remains unmeasured.

The strict budget scenario deliberately omits video because finalization has a
separate budget. Neither agent claimed a whole-process wall-clock guarantee.
Project reinstall commands and scenario-specific clean-state requirements remained
necessary inputs, not something the agent invented.

## Executable checks and measurement limits

- Local `simctl help openurl`, `terminate`, and `uninstall` confirmed syntax.
- Android `am help` and `pm help` confirmed native lifecycle commands.
- On `emulator-5554`, a non-mutating `adb shell printf` round trip with the
  documented two-level quoting returned exactly
  `shop://checkout/cart?coupon=A%26B&source=test`.
- `LoggingE2ETest` failed before logger configuration: routine INFO plus full
  logger/thread prefixes appeared. The packaged configuration suppresses INFO
  while retaining WARN/ERROR and unchanged JSON stdout, verified in a fresh JVM.
- `InitCommandE2ETest` verifies the packaged instruction body and refreshes stale
  content for all three supported agents to the same generated content.

No app was uninstalled, reset or navigated for these documentation trials. There
are no end-to-end device-session durations, actual token counts or token estimates.
No #57 performance trace is claimed for simulated commands. Future live trials
should use the [performance guide](../performance.md), capture available agent
usage separately, and record unnecessary help calls, inspections and retries.
Body bytes and proposed command counts are not token or reasoning savings.

## Shortened skill revision

Following user review, the distributed skill was reduced from 4,375 to 1,672
whitespace-delimited words (523 to 200 lines). All MCP and tracing instructions
were removed; those remain in product documentation. This is a measured text-size
reduction, not a token or execution-performance measurement. The preceding trial
table describes the longer revision, not this replacement.

The packaged initialization tests were updated for the CLI-only body and passed
against the shortened resource. Native commands, structured outcomes, built-in
waits, recording exceptions and reset limitations remain self-contained.

A follow-up simulated trial retained correct choices for waits, functional
evidence, recording, errors and native lifecycle. It identified three avoidable
help calls because timeout flag syntax had been removed. The final text restores
those flag names and millisecond units in two lines. No live-model token or
runtime measurement was collected.
