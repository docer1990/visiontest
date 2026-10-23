# Test mobile apps with VisionTest

## Workflow

Establish the platform, app ID, target, starting state and expected outcome.
Follow the project's build/install instructions; reused builds must contain the
code under test. Never emit a `sleep` command, including `sleep 0` as a placeholder
or separator. Fixed delays and manual polling are not readiness checks for app
startup, navigation, swipes, animations or loading. Use the readiness rules below.
Execute requested checks in this order:

1. Identify the requested scenario and assertions. Reuse known selectors; discover
   only what is missing. Setup, exploration and recovery run without recording.
2. Batch known exploratory actions with `flow --json` without `--record`. End at
   decisions needing new UI information, inspect that state and continue discovery.
   Knowing the next few actions does not make the whole scenario ready to record.
3. Start `flow --record --json` only for an actual requested test whose starting
   state, complete action sequence, selectors, test data and expected assertions
   are established. State which scenario this run verifies. Before every recorded
   flow, terminate the app, inspect the result, relaunch it and inspect that result.
   Then execute the complete scenario through its final outcome wait in one flow.
   A fully defined scenario can be recorded on its first execution; readiness does
   not require a rehearsal or certainty that the test will pass.
   An explicit request to rerun a previously tested scenario is a new pending test.
   If observed state contradicts a prerequisite, resolve it before recording.
   Missing prerequisites block a test; an unmet assertion with valid prerequisites
   fails it. Never replace the expected outcome with the observed one just to pass.
4. Evaluate structured results against the requested assertions. Check intermediate
   states only for decisions or separate assertions. Apply the screenshot rules below.
5. Report passed/failed/blocked checks, expected/observed outcomes, evidence paths
   and any test recording omission with its reason. Unrecorded exploration is normal.
   Stop when requested checks finish; never replay completed actions merely to
   convert them into a flow or obtain video.

## Session setup

Check `visiontest --version` once; use command `--help` for unknown syntax.
Always supply a command. VisionTest fragments omit the `visiontest` prefix;
device commands require `-p android` or `-p ios`. Replace placeholders with
validated target values. iOS supports simulators only.

Android needs `install_automation_server` before first use/after APK-pair upgrades;
iOS needs a matching XCUITest bundle or configured source build.
Check `available_device --json` and `automation_server_status`. Resolve ambiguity
before mutations; there is no device-selection flag or backend identity verification.
Retain the serial/UDID. Use `start_automation_server` and `launch_app APP_ID` as needed.
Reuse device/server setup across tests/retries until target changes, restarts,
disconnects or failures. App reuse does not waive the restart before a recorded flow.

At task end, stop only servers you started unless asked otherwise.
Android uses `stop_automation_server`; inspect warnings. iOS stop cannot control
another CLI invocation's process. Check `automation_server_status`; if still active
or unverifiable, report cleanup as incomplete. Never kill unrelated processes.

## App readiness

Run dependent standalone commands separately and inspect each result before continuing.
Do not chain them with `;`; `&&` alone also misses operation failures returned with
exit 0. Use flow for supported action sequences so a failed step stops later actions.

For example, launch the iOS app:

```bash
visiontest launch_app com.example.app -p ios
```

Inspect stdout, stderr and exit status. Stop on launch failure. If `email` is a
known ready control, await it without a delay before or after:

```bash
visiontest wait_for_element -p ios --bundle-id com.example.app --resource-id email --json
```

When the next operation is already a targeted tap/input, its actionability wait
replaces a separate readiness wait. If no ready selector is known, immediately use
`get_interactive_elements -p ios --bundle-id com.example.app --compact` instead.
Use discovered selectors to await the expected destination or a loader's disappearance
with `wait_for_element --gone`. Do not invent selectors or repeatedly query until
the screen "looks stable". If readiness cannot be expressed, report that limitation
rather than substituting a delay. A successful launch alone does not prove UI readiness.

## Flow execution and recording

Serialize device actions. Run server setup, app launch and standalone inspection
commands outside flow.
Use standalone actions for exploration, recovery or operations unsupported by flow;
batch known actions whenever possible. Choose recording by the purpose of the run:

| Run | Command |
| --- | --- |
| Explore navigation, discover selectors, resolve an unknown branch, prepare/recover state | `flow --json` without `--record` |
| Execute a complete, defined scenario that still needs testing | `flow --record --json` |

Every recorded test starts with a fresh app launch, including the first run,
consecutive tests and requested reruns. A ready screen or previously explored path
does not waive termination and relaunch. Keep the healthy automation server running.
After relaunch, include the navigation needed to reach the test state in the recorded
flow; do not start recording only from the screen left by exploration. Restarting
does not clear persisted app data. Establish scenario-specific data prerequisites.
Exploratory flows do not require a restart.

Use one recording for the complete scenario, not a video per action or exploratory
segment. If unexpected UI requires discovery, inspect it without recording before
planning any further test; do not start successive recorded fragments.

For a ready test, recording is required unless the user opts out or a concrete
recorder limitation, unsupported flow operation or incompatible budget prevents it.
Report that limitation before unrecorded execution; do not call partial recordings
complete scenario evidence. Convenience or a generic desire for speed is no exception.

For this complete iOS example, the app opens on the form and the requested scenario
ends at `done`. First terminate the app on the established simulator:

```bash
xcrun simctl terminate "UDID" "com.example.app"
```

Inspect termination's exit status and diagnostics. Continue only after successful
termination or a specific diagnostic confirming the app is already stopped. Other
failures block launch and testing; never suppress them with `|| true`. Then launch:

```bash
visiontest launch_app com.example.app -p ios
```

Inspect stdout, stderr and exit status; a returned launch failure blocks the flow
even with exit 0. Only after successful launch run the complete recorded scenario:

```bash
visiontest flow -p ios --record --json \
  --step 'input_text "user@example.com" --target-resource-id email --bundle-id com.example.app' \
  --step 'tap_on_element --resource-id continue --bundle-id com.example.app' \
  --step 'wait_for_element --resource-id done --bundle-id com.example.app --timeout 10000'
```

Choose exactly one source, repeated `--step`, `--file PATH` or `--stdin`. Steps accept quotes,
not shell expansion, platform or `--json` flags. See `flow --help` for other allowed commands.
Before recording, review the final steps, selectors, assertions and timeout budgets.
For `--file`, read the saved file after the last edit, including scripted edits.
Preserve requested assertions; remove a readiness check only when its purpose is
covered elsewhere. Choose explicit timeouts for scenario assertions from the test
requirements and evidence. Do not silently replace an established timeout with
the 10,000 ms default when transferring exploratory steps into a flow; a longer
flow budget does not extend an element wait. Failed waits require investigation,
not automatic timeout increases or another unchanged attempt just for video.
At a decision boundary, use `--observe interactive-elements --compact` for discovery;
if the destination has no known selector, inspect this observation before claiming success.
`--on-failure` accepts `interactive-elements`, `hierarchy`, `screenshot`; screenshot
rules still apply. Outer iOS `--bundle-id` scopes observations, not steps.

Check overall `success`, step results and, when recording, `recording.status/path/error`.
A successful action or saved video alone does not prove the expected app outcome. Recording
failure fails the flow even if steps pass; report both results without silently
retrying unrecorded. Do not routinely analyze recordings.

Default millisecond budgets are `--step-timeout` 35000, `--timeout` 120000,
`--evidence-timeout` 5000. Failure evidence has a separate budget. Recording startup
shares the overall budget; finalization adds up to 30000 ms. Android recorder capacity
must cover overall + requested evidence + finalization. Fit the complete test within
supported budgets or report the limitation; do not omit assertions to fit a video.
Overall timeout is not a wall-clock limit.

## Operations and selectors

Reuse known selectors. `--bundle-id` is command-specific, not a global iOS flag.
Supply it to supported element queries/actions such as `tap_on_element`; never
attach it to `swipe_direction` or `screenshot`. Check each option against the exact
command before execution; use that command's `--help` if uncertain.

| Need | Command |
| --- | --- |
| Check now | `find_element --resource-id ID --json` |
| Await outcome | `wait_for_element --text "Expected" --json`; `--gone` for disappearance |
| Tap | `tap_on_element --resource-id ID --json` |
| Fill | `input_text "value" --target-resource-id ID --json` |
| Discover | `get_interactive_elements --compact`; filter with `--type TYPE` / `--region x,y,width,height` |
| Reveal content | `swipe_on_element up --resource-id CONTAINER_ID --json`; without a container, `swipe_direction up --distance medium --json` |
| Missing structure | `get_ui_hierarchy` when narrower queries cannot answer the assertion |

Prefer `tap_on_element` for a uniquely identified control. It does not scroll the screen.
Prefer IDs; compact `resourceId`, `contentDescription`, `text` map to `--resource-id`,
`--content-description`, `--text`; input uses `--target-*`. Flutter often uses
`contentDescription`. `--bundle-id` only scopes an iOS app and never replaces an element selector;
Android rejects it. iOS selectors do not combine with AND.

Disambiguate `selectorMatches > 1`; its absence does not guarantee uniqueness.
Filters affect observations, not action targets. `--type` matches native class;
`--region` filters centers (Android pixels/iOS points). Both require `--compact`.
Use `--include-bounds` or `--include-disabled` when needed. Android discovery filters
visible elements; iOS may include offscreen elements. Presence is not actionability;
filtered absence is not app-wide absence.

Selector taps, long/double taps and
targeted input wait for actionability; they default to 10,000 ms and accept timeouts from 1 through 30,000 ms
via `--timeout MS`. Element waits share these defaults and bounds.
Avoid redundant readiness checks; afterwards use `wait_for_element`
to verify the outcome. Coordinates, untargeted input and swipes lack selector waits.
Unsupported conditions need project checks or reported limitations.

Android targeted input replaces; empty clears. Android `clear_text` clears the focused
field. iOS types without guaranteed replacement; there is no generic iOS `clear_text` command.
Android supports `press_key`/`press_back`; iOS uses controls, `dismiss_keyboard`, or
`handle_alert accept --button-label "Exact label"`.

Swipe visible containers, not offscreen children. `up` reveals below; `left` reveals
right. Each call performs one gesture. Bound attempts; stop on unchanged contents/failure.

For exploration with an already identified, unique target after a swipe, batch
the gesture and tap without recording or a delay; the tap waits for actionability:

```bash
visiontest flow -p ios --json \
  --step 'swipe_direction up --distance medium' \
  --step 'tap_on_element --bundle-id com.example.app --text "Known item"'
```

If the next target is unknown, end after the display-wide swipe with
`--bundle-id com.example.app --observe interactive-elements --compact` on the flow,
inspect the result, then choose the next action. Verify any known navigation outcome
with an explicit wait; do not treat the successful tap as proof of that outcome.

## Evidence and screenshots

Use `find_element --json` for current presence/state, `wait_for_element --json`
for appearance/disappearance, and compact discovery for unfamiliar controls.
Successful flow waits provide the same functional evidence; do not repeat them
as standalone commands or confirm them with screenshots. A request to "verify
visually" that a control appears still uses CLI evidence when it answers the assertion.

Capture a screenshot only to document an observed app bug or when the exact
assertion cannot be resolved through available CLI queries. Before capture, state
the bug or the missing fact and why structured evidence cannot establish it.
For a UI bug, capture the failing state before recovery when possible, alongside
the structured failure. For unsupported evidence, use relevant selector queries,
bounds or hierarchy first; pixel appearance, clipping and occlusion may require an image.
Presence alone does not prove onscreen visibility, especially on iOS; bounds alone
do not prove that another view is not covering the element. Do not tap or scroll
just to prove visibility if that changes the state being checked.

Do not take routine before/after or per-step screenshots, add `--observe screenshot`
to successful functional flows, or use an image merely for reassurance or a report
template. A generic request for evidence does not require images. Prefer
`--on-failure interactive-elements --compact` with outer iOS `--bundle-id APP_ID`.
Do not add `--on-failure screenshot` by default: justify it before execution with
the known UI bug being reproduced or the specific assertion requiring image evidence.
A functional timeout alone does not establish that justification. Apply these rules
to standalone screenshots, flow steps and observation/failure options alike.
When justified, use `screenshot --output ./evidence.png`, open the image before
visual claims and report its path. If capture fails, retain the structured evidence
and report the missing artifact.

## Native lifecycle

Use the established serial/UDID and app ID, never ambiguous `booted`.
Deep links must not bypass navigation under test.
Set `URL='shop://checkout/cart?$filter=name&source=test'` before Android launch.

| Action | Android | iOS |
| --- | --- | --- |
| Open URL | `adb -s "SERIAL" shell am start -W -a android.intent.action.VIEW -d "'$URL'" -p "PACKAGE"` | `xcrun simctl openurl "UDID" 'URL'` |
| Terminate | `adb -s "SERIAL" shell am force-stop "PACKAGE"` | `xcrun simctl terminate "UDID" "BUNDLE_ID"` |
| Clear data | `adb -s "SERIAL" shell pm clear "PACKAGE"` | No equivalent |
| Uninstall | `adb -s "SERIAL" uninstall "PACKAGE"` | `xcrun simctl uninstall "UDID" "BUNDLE_ID"` |

For example, close the app after the final requested form-to-done flow above.
First wait for the flow process to return, inspect its results and recording
status/path/error, and collect any needed bug evidence before changing the screen.
Then, if no remaining test needs the app state and the app should be closed, run:

```bash
xcrun simctl terminate "UDID" "com.example.app"
```

Use the established simulator UDID. Termination is a separate native command,
not a flow step or `visiontest terminate_app`. Wait for any preceding recording to
finalize before the mandatory restart for the next recorded test. Unrecorded
exploratory segments can reuse app state. Report cleanup failures separately from
test results.

For a requested rerun, follow the same terminate/check/launch/check/record sequence
above. Reuse known selectors, data and assertions; selector actions wait for readiness.
If initial readiness needs a separate known condition, include its wait in the flow.
Do not add sleeps or rediscovery merely because the app restarted. If unexpected UI
invalidates the path, explore without recording, then restart again before the
newly defined recorded test. Explicitly requested reruns remain allowed after a pass.

`-d "'$URL'"` preserves `$` across both shells. Preserve paths/query/encoding;
encode apostrophes as `%27`. Inspect launch diagnostics even on exit 0;
verify the scoped outcome. iOS `openurl` may route elsewhere.

Reset only when required. iOS reinstall is not Android `pm clear`;
verify isolation of Keychain/shared/remote state.

## Results and recovery

Retain JSON stdout, stderr and exit status; never filter with `grep -v INFO`.
Use selector queries for UI assertions; a `grep` match on a label is not a result
check. Any extracted summary must preserve access to the complete JSON and errors.
Check `error` before `success/found/satisfied`, including standalone exit 0.
Successful disappearance returns `found:false`, `condition:"gone"`, `satisfied:true`.
Standalone wait timeouts leave stdout empty.

Flows fail nonzero, skip remaining steps and retain results without rollback.
Inspect uncertain action outcomes before retrying; require new evidence/correction.
Failed prerequisites block dependent checks; restore starting state for independent scenarios.
Connection failures need device/server checks; unknown methods usually need matching
Android APKs/iOS bundles and restart. Use logs only to explain failures.
