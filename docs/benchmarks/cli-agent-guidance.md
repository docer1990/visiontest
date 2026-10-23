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

## Recorded segments and screenshot restrictions

On 2026-10-23, another revision tested the 970-word instruction body against its
1,420-word replacement. Two fresh agents using the session's inherited model read
only the corresponding body and proposed commands for nine simulated situations.
The baseline received the first six scenarios together and the last three in a
follow-up; the updated agent received all nine together. This is a qualitative
instruction check, not a controlled model benchmark or live device execution.

The shared setup specified a healthy automation server and recorder, sufficient
recording budgets and known unique selectors. Pressures included twenty minutes
of manual exploration, a near deadline, commands already prepared individually,
a report template expecting a screenshot and several preceding failed builds.

| Scenario | Previous instructions | Updated instructions |
| --- | --- | --- |
| Second checkout check after manual exploration | Recorded flow, structured outcome evidence | Same; deadline and prepared individual commands do not waive recording |
| First form execution with known selectors | Recorded flow without rehearsal | Same |
| Home presence passed; screenshot field in report | Reused wait result, no image | Same |
| iOS label visibility without scrolling | Checked bounds, image if unresolved | Same; explicitly distinguishes bounds from absence of occlusion |
| Unknown choice after form submission | Flow to decision boundary, then structured discovery | Same; new recorded segment after choosing the branch |
| Completed checks without video; later recorder finalization failure | No replay; reported recording failure separately from UI success | Same |
| “Verify visually” that Continue appears, already established by CLI | Added screenshot | Reused structured results, no further command |
| Reproducible UI bug after failed wait | No screenshot required | Captured failing state before recovery, retained structured failure |
| Checklist labels Saved banner presence “visual verification” | Added screenshot | Reused structured results, no screenshot |

The baseline justified its Continue screenshot with “because the user explicitly
requested visual verification”. For the Saved banner it cited “screenshots serve
visual checks, inaccessible content, ambiguity or image requests”. The updated
agent instead stated that “Verify visually” and “provide evidence” do not
independently require an image when CLI evidence answers the assertion. It also
proposed a screenshot for the actual UI bug before recovery.

This trial reproduced two unnecessary screenshot choices, but did not reproduce
reluctance to use recorded flows in the baseline. The revision makes the transition
to flow explicit rather than claiming a measured improvement in flow adoption.
The added words specify that transition, recording exceptions and the screenshot
decision. There is no guarantee of compliance across models or repeated sessions.

Verification used the existing `InitCommandTest` and `InitCommandE2ETest` suites
to check classpath guidance, JAR packaging, self-contained generated content and
replacement of stale skills for all supported agents. No new assertions tied to
exact prose were added. Generated skills in client projects still require
reinitialization using a binary containing the updated resource.

## Record complete tests, not exploratory segments

User feedback after trying the preceding revision reported too many videos during
exploration. Two further fresh agents using the session's inherited model evaluated
the same eight simulated situations before and after separating flow batching from
recording. Each read only its instruction body. The baseline was the 1,420-word
revision above. These are proposed commands, not device executions.

| Situation | Segment recording rule | Scenario recording rule |
| --- | --- | --- |
| Only cart navigation known; shipping/payment still unknown, deadline near | Recorded cart flow | Unrecorded cart flow with structured discovery |
| Login submission known; next screen and branch unknown | Recorded submission flow | Unrecorded submission flow with structured discovery |
| Complete form-to-done test defined before first execution | One recorded flow, no rehearsal | Same |
| All requested assertions already verified during exploration | Stop, no video replay | Same; unrecorded exploration is normal |
| Independent alternate-coupon test still pending, complete path known | Recorded flow | Same |
| Complete test, recorder unavailable with diagnostic | Explain limitation, unrecorded flow | Same |
| UI assertions passed, recording finalization failed | Report flow failure, no replay | Same |
| Home wait passed; generic request for strong evidence | No screenshot | Same |

The baseline explicitly chose “Record the known cart segment now” and “Use a
recorded flow with compact discovery at the unknown destination”. The replacement
chose “Continue discovery without recording” and “Submit the known login segment
without recording, then inspect”. It required a complete scenario definition before
recording, without requiring a prior passing run. Existing screenshot restrictions
and recording failure semantics remained intact in these trials.

This supersedes the earlier recommendation to record each known segment. It does
not establish actual video counts, model-wide compliance or runtime savings.

## Readiness, gestures and app termination

Further user examples showed fixed sleeps after launch, termination, swipes and
taps, including during an explicitly requested restart and rerun of a known
scenario. One swipe also supplied unsupported `--bundle-id`; unconditional shell
chaining would still attempt the following tap after argument validation failed.

Two fresh agents compared the instruction bodies using seven simulated lifecycle
cases: unfamiliar launch under deadline pressure, known readiness selector, a
launch failure returned with exit 0, a loading state, termination during video
finalization, state reuse between tests, and bug evidence before cleanup. The
updated agent additionally evaluated known-target and unknown-target swipes,
then explicit restart-and-rerun and failed-launch variants in a follow-up.

Both agents rejected sleeps in the original seven cases. This trial therefore
did not reproduce the user's delay behavior. The baseline did propose an invalid
`--bundle-id` on `screenshot`; the revision explicitly limits that option and the
updated agent omitted it. The updated agent selected an unrecorded swipe/tap flow
for a known target and an unrecorded swipe with compact observation for an unknown
target. It reused the complete recorded scenario for an explicit rerun, rejected
execution after a failed launch even with exit 0, and delayed termination until
recording finalization and required evidence collection were complete.

Source inspection confirmed that `swipe_direction` and `screenshot` lack a
`--bundle-id` option. Local `xcrun simctl help terminate` confirmed the device and
bundle-identifier syntax. No application was launched, swiped or terminated for
these checks. The existing initialization and packaged-resource suites validate
distribution; these simulated choices do not guarantee compliance by other models.

## Mandatory restart before recorded tests

The user subsequently required termination and relaunch before every recorded
flow, not only explicit restart requests. Two fresh agents using the session's
inherited model evaluated the same six simulated situations with the old and
revised instruction bodies. The baseline skipped restart for a ready first-test
screen and consecutive recorded tests, and allowed a test to begin on an explored
detail screen when that qualified as its starting state.

The updated agent required terminate/check/launch/check before the first recorded
test, repeated that preparation before the next recorded test, and included the
known navigation from fresh launch in the recording. Both versions kept pure
exploration unrecorded without a restart, blocked launch after simulator connection
failure during termination, and blocked a flow after a launch failure returned
with exit 0. No device actions were executed for this comparison.

This requirement supersedes earlier guidance permitting app-state reuse between
recorded tests. Device/server setup can still be reused, and app restart does not
clear persisted test data. The initialized skill copy is checked against the
source body; existing initialization and packaging tests cover distribution.

## Final flow review and contradictory state

A subsequent user report described expired 20/30-second waits for “I am ready”,
structured “I am charging” output, a saved flow reverting to the default 10-second
wait, a Toolbar wait removed with `sed`, and generic screenshot failure evidence.
The reported flow file was not present at the supplied local project path, so
the actual commands, prerequisites and artifacts could not be independently audited.

Two fresh agents applied the before/after bodies to the same six decision areas.
The baseline already distinguished missing prerequisites from failed assertions,
preserved the requested expectation and restricted screenshots. It identified
that final-file rereading was not explicitly required. The updated agent explicitly
required reading the final file after editing, choosing an assertion timeout from
requirements and evidence rather than automatically adopting either 10 or 30 seconds,
and resolving the purpose of the removed Toolbar check. It preserved the ability
to record a failing test when prerequisites are valid, rejected grep-only status
evidence, and distinguished potentially justified occlusion images from unnecessary
screenshots of status text. These were simulated decisions, not a live replay.

The revision adds explicit final-flow review and timeout preservation and clarifies
failure evidence. It does not establish that charging was a product bug, that the
Toolbar removal was wrong, or that every reported screenshot was unnecessary.

The user clarified that the main violations were unsupported `--bundle-id` on
`swipe_direction` and an emitted `sleep 0`. The revision moves the command-specific
scope restriction into the main selector guidance and explicitly prohibits zero
sleep placeholders. Local packaged-CLI `swipe_direction --help` confirms the
accepted options. A fresh agent rejected both rationalizations and selected the
unrecorded swipe/tap flow plus the valid standalone swipe syntax, with no sleep
or bundle scope on the swipe. No device commands were executed for this trial.
