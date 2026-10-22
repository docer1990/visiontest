# Test mobile apps with VisionTest

## Workflow

Identify the platform, app ID, target device, starting state and expected result
from the application project. Follow its AGENTS.md or equivalent for builds,
artifacts, variants, simulator builds and installation. Reuse only a build
containing the code under test.

Check intermediate states only when needed for a decision or a separate assertion.
Use structured results as functional evidence. Screenshots are for visual
assertions, inaccessible content, unresolved ambiguity or explicit image requests.
Open a captured image before making a visual claim. Do not routinely analyze video.

Report passed, failed and blocked checks, expected versus observed results and
evidence paths. Stop when the requested checks are complete.

## Prepare once, reuse setup

Check `visiontest --version` once per session. If absent from PATH, try
`~/.local/bin/visiontest`; if missing, follow the project's installation instructions.
Use `visiontest <command> --help` only for unknown syntax. Always supply a command.
Device commands require `-p android` or `-p ios`.

Android needs Java 17+, Platform Tools and an authorized device/emulator. iOS needs
Java 17+, macOS, full Xcode and a compatible simulator runtime; physical iOS devices
are unsupported. Use the installed iOS test bundle or a configured source build.

Check `available_device -p PLATFORM --json` and `automation_server_status -p PLATFORM`.
There is no CLI device-selection flag or runtime verification of backend identity.
Use one intended target and configure its server consistently; resolve ambiguous
or mismatched targets before changing state. Retain its Android serial or iOS UDID
for native commands.

On Android, run `install_automation_server -p android` before first use and after
upgrading the automation APK pair. On iOS, use the matching XCUITest bundle, not
`install_automation_server`. If needed, select a simulator with
`xcrun simctl list devices available`, then `xcrun simctl boot "UDID"` and
`xcrun simctl bootstatus "UDID" -b`.

Start with `start_automation_server -p PLATFORM` only when needed, then
`launch_app -p PLATFORM APP_ID` if the scenario requires it. Recheck setup only
after a target change, restart, disconnect or relevant failure. At task end, stop
only a server you started with `stop_automation_server -p PLATFORM`, unless asked
to leave it running. Keep it running across the requested tests and retries.

All command fragments above require the `visiontest` prefix. Replace placeholders
with established project/device values; do not execute them literally.

## Choose the operation

Reuse known selectors; discover only unknown controls.
Commands below omit `visiontest` and `-p PLATFORM`. For iOS element operations,
add `--bundle-id APP_ID`; Android rejects it.

| Need | Command |
| --- | --- |
| Check a known element now | `find_element --resource-id ID --json` |
| Await appearance/disappearance | `wait_for_element --text "Expected" --json`, add `--gone` for disappearance |
| Tap a control | `tap_on_element --resource-id ID --json` |
| Fill a field | `input_text "value" --target-resource-id ID --json` |
| Discover unknown selectors | `get_interactive_elements --compact` |
| Reveal content in a visible container | `swipe_on_element up --resource-id CONTAINER_ID --json` |
| Scroll without a container selector | `swipe_direction up --distance medium --json` |
| Inspect information missing from compact output | `get_ui_hierarchy` |
| Capture visual evidence | `screenshot --output ./evidence.png` |

Prefer `tap_on_element` for a uniquely identified control, using stable IDs over
coordinates. It does not scroll the screen. Compact `selector` keys `resourceId`,
`contentDescription` and `text` map to CLI `--resource-id`, `--content-description`
and `--text`; input uses `--target-*`. On Flutter, labels often use
`contentDescription`. `--bundle-id` only scopes an iOS app and never replaces an element selector.
Use one distinctive selector; iOS lookup does not combine multiple selectors with AND.

Do not add sleeps or manual polling for supported UI conditions. `tap_on_element`,
selector-based `long_press`/`double_tap` and targeted `input_text` already wait for
an actionable target. Their waits default to 10,000 ms and accept timeouts from 1 through 30,000 ms.
Invoke them directly, without a preliminary find/wait. Afterwards use
`wait_for_element` for the expected app outcome; successful interaction is not
proof of that outcome. `find_element` checks the current state. Coordinate actions,
untargeted input and swipes do not provide selector waits. A timeout is a maximum,
not a fixed delay. For unsupported readiness conditions use the project's check
or report the limitation, rather than guessing a sleep.

Android targeted input replaces the field value; an empty value clears it. iOS
targeted input types without promising replacement; there is no generic iOS `clear_text` command.
Android `clear_text` affects the focused field. `press_key` and `press_back` are
Android-only. On iOS use visible controls, `dismiss_keyboard`, or
`handle_alert accept --button-label "Exact label"` for the intended alert choice.

For offscreen targets, swipe the visible container, not the offscreen child.
`up` reveals content below; `left` reveals content to the right. Each call performs
one gesture, not scroll-until-found. Bound attempts and check progress; stop on
unchanged contents or failure. Use compact `--include-bounds` when geometry helps.
Android discovery filters visible elements; iOS can include offscreen elements.
Presence does not prove actionability, and absence from filtered discovery does
not prove absence from the app. Add `--include-disabled` when relevant.

## Batch known actions and record evidence

Batch known actions and end with an explicit outcome wait. Serialize actions on
one device. Launch/setup and discovery happen outside the flow. This iOS example
uses known IDs and scopes each step explicitly:

```bash
visiontest flow -p ios --record --json \
  --step 'input_text "user@example.com" --target-resource-id email --bundle-id com.example.app' \
  --step 'tap_on_element --resource-id continue --bundle-id com.example.app' \
  --step 'wait_for_element --resource-id done --bundle-id com.example.app'
```

Use exactly one source: repeated `--step`, `--file PATH` or `--stdin`. Steps use
CLI arguments without platform or `--json` flags. Quoted arguments are supported;
shell variables and substitutions are not expanded by the flow parser.

| Platform | Allowed steps |
| --- | --- |
| Both | `tap_by_coordinates`, `tap_on_element`, `input_text`, `long_press`, `double_tap`, `swipe`, `swipe_direction`, `swipe_on_element`, `press_home`, `wait_for_element`, `screenshot` |
| Android | `press_back`, `press_key`, `clear_text` |
| iOS | `dismiss_keyboard`, `handle_alert` |

All other commands run separately. For final discovery when needed, add
`--observe interactive-elements --compact`; `--on-failure` accepts
`interactive-elements`, `hierarchy` or `screenshot` for failure evidence.
An outer iOS `--bundle-id` scopes observations, not steps.

Include `--record` on test flows unless the user requests otherwise or recorder
limitations/time budgets prevent it; report the omission. Video captures the whole
display without audio. It supports evidence, not automatic verification. Check
`recording.status`, `path` and `error`. Recording failure fails the flow even if
UI steps passed. Do not silently drop recording or replay completed actions just
to obtain video.

Set selector-action/wait budgets with `--timeout MS`. Flow accepts `--timeout MS`,
`--step-timeout MS` and `--evidence-timeout MS`; values are milliseconds.
Default budgets are 35 seconds per step and 120 seconds overall. Recording startup
shares the overall budget; finalization can add up to 30 seconds. Failure evidence
has a separate 5-second default budget. Android recording must support the overall
budget plus evidence and finalization, otherwise startup fails before actions.
Use shorter flows or explain the limitation; a flow timeout is not a strict
whole-process wall-clock limit.

## Native app lifecycle

Use native commands to establish the starting state. Deep links may shorten setup
only when skipped navigation is not under test.

Replace `SERIAL`, `UDID`, `PACKAGE`, `BUNDLE_ID` and `URL` with validated nonblank
values for the intended target. Do not use an arbitrary device or ambiguous `booted`.

| Action | Android | iOS Simulator | Expected native result |
| --- | --- | --- | --- |
| Open URL | `adb -s "SERIAL" shell am start -W -a android.intent.action.VIEW -d "'URL'" -p "PACKAGE"` | `xcrun simctl openurl "UDID" 'URL'` | Launch request accepted; verify the screen separately |
| Terminate | `adb -s "SERIAL" shell am force-stop "PACKAGE"` | `xcrun simctl terminate "UDID" "BUNDLE_ID"` | App stopped; inspect diagnostics if already stopped |
| Clear data | `adb -s "SERIAL" shell pm clear "PACKAGE"` | No equivalent here | Android reports `Success`; app remains installed |
| Uninstall | `adb -s "SERIAL" uninstall "PACKAGE"` | `xcrun simctl uninstall "UDID" "BUNDLE_ID"` | App removed; Android reports `Success`, simctl normally exits silently |

Android URL quoting must survive both host and device shells. Preserve paths and
query parameters, for example `-d "'shop://checkout/cart?coupon=A%26B&source=test'"`.
Percent-encode literal apostrophes as `%27` for these forms without changing existing
encoding. Check exit status and diagnostics, including Android launch errors even
on exit 0. Then verify the application outcome with a scoped query/wait. iOS
`openurl` may route to another app and takes no bundle ID.

Reset/uninstall only when required. iOS uninstall and project-guided reinstall
are not equivalent to Android `pm clear` and do not guarantee removal of Keychain,
shared or remote state. Verify the isolation the scenario actually needs.

## Check results and recover

Use `--json` where supported. Keep stdout results, stderr diagnostics and command
exit status; do not filter with `grep -v INFO` or discard stderr. Routine INFO logs
are suppressed by default. Check `error` first, then `success`, `found` or
`satisfied` as appropriate. Standalone commands can return operation failures with
exit 0. A successful disappearance wait has `found:false`, `condition:"gone"`,
`satisfied:true`; absence is not itself an error. Wait timeouts leave stdout empty.
Exit codes 1–5 mean generic failure, invalid arguments, server unreachable,
device unavailable and unsupported platform respectively.

Flows exit nonzero for returned operation failures and skip subsequent steps.
After execution starts, the JSON report retains step/evidence results even on
failure. Actions are not rolled back. After an action timeout, inspect the expected
result before retrying because the action may have happened. Retry only after new
evidence or a corrective action. Failed prerequisites block dependent checks;
continue independent scenarios only when their starting state can be restored.

For connection failures, check device/server status. Method-not-found usually
requires updating the Android APK pair or iOS bundle to match the CLI, then
restarting. Use device logs only when needed to explain a failure.

## Refresh the skill

After upgrading the CLI, run `visiontest init --agent claude,opencode,codex` in each
project, selecting only the agents used. It overwrites each selected `SKILL.md` completely
with the offline instructions embedded in that JAR, including its version marker.
