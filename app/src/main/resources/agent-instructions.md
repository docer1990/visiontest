# Test mobile apps with VisionTest

## Workflow

Establish the platform, app ID, target, starting state and expected outcome.
Follow the project's build/install instructions; reused builds must contain the
code under test. Check intermediate states only for decisions or separate assertions.
Use structured functional evidence; screenshots serve visual checks, inaccessible
content, ambiguity or image requests. Open images before visual claims.
Report passed/failed/blocked checks, expected/observed outcomes and evidence paths.
Stop when requested checks finish.

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
Reuse setup across tests/retries until target changes, restarts, disconnects or failures.

At task end, stop only servers you started unless asked otherwise.
Android uses `stop_automation_server`; inspect warnings. iOS stop cannot control
another CLI invocation's process. Check `automation_server_status`; if still active
or unverifiable, report cleanup as incomplete. Never kill unrelated processes.

## Operations and selectors

Reuse known selectors. Scope iOS element queries/actions with `--bundle-id APP_ID`.

| Need | Command |
| --- | --- |
| Check now | `find_element --resource-id ID --json` |
| Await outcome | `wait_for_element --text "Expected" --json`; `--gone` for disappearance |
| Tap | `tap_on_element --resource-id ID --json` |
| Fill | `input_text "value" --target-resource-id ID --json` |
| Discover | `get_interactive_elements --compact`; filter with `--type TYPE` / `--region x,y,width,height` |
| Reveal content | `swipe_on_element up --resource-id CONTAINER_ID --json`; without a container, `swipe_direction up --distance medium --json` |
| Missing structure / visual evidence | `get_ui_hierarchy` / `screenshot --output ./evidence.png` |

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

Use built-in waits, not sleeps/manual polling. Selector taps, long/double taps and
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

## Recorded flows

Serialize device actions. Batch known steps and finish with an outcome wait;
setup/launch/discovery run separately.

```bash
visiontest flow -p ios --record --json \
  --step 'input_text "user@example.com" --target-resource-id email --bundle-id com.example.app' \
  --step 'tap_on_element --resource-id continue --bundle-id com.example.app' \
  --step 'wait_for_element --resource-id done --bundle-id com.example.app'
```

Choose exactly one source, repeated `--step`, `--file PATH` or `--stdin`. Steps accept quotes,
not shell expansion, platform or `--json` flags. See `flow --help` for other allowed commands.
Final discovery uses `--observe interactive-elements --compact`; `--on-failure` accepts
`interactive-elements`, `hierarchy`, `screenshot`. Outer iOS `--bundle-id` scopes observations,
not steps.

Include `--record` unless user preference, recorder limitations or budgets prevent it;
report omissions. Check `recording.status/path/error`; recording failure fails the flow
even if steps pass. Never replay actions for video or routinely analyze recordings.

Default millisecond budgets are `--step-timeout` 35000, `--timeout` 120000,
`--evidence-timeout` 5000. Failure evidence has a separate budget. Recording startup
shares the overall budget; finalization adds up to 30000 ms. Android recorder capacity
must cover overall + requested evidence + finalization. Shorten unsupported flows;
overall timeout is not a wall-clock limit.

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

`-d "'$URL'"` preserves `$` across both shells. Preserve paths/query/encoding;
encode apostrophes as `%27`. Inspect launch diagnostics even on exit 0;
verify the scoped outcome. iOS `openurl` may route elsewhere.

Reset only when required. iOS reinstall is not Android `pm clear`;
verify isolation of Keychain/shared/remote state.

## Results and recovery

Retain JSON stdout, stderr and exit status; never filter with `grep -v INFO`.
Check `error` before `success/found/satisfied`, including standalone exit 0.
Successful disappearance returns `found:false`, `condition:"gone"`, `satisfied:true`.
Standalone wait timeouts leave stdout empty.

Flows fail nonzero, skip remaining steps and retain results without rollback.
Inspect uncertain action outcomes before retrying; require new evidence/correction.
Failed prerequisites block dependent checks; restore starting state for independent scenarios.
Connection failures need device/server checks; unknown methods usually need matching
Android APKs/iOS bundles and restart. Use logs only to explain failures.
