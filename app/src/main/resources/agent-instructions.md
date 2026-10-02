# VisionTest Mobile App Testing

Use VisionTest to give a coding agent eyes and hands on an Android app or iOS
simulator. The goal is to close the implementation feedback loop: exercise a
change end to end, reproduce a bug, capture UI evidence, correlate it with logs,
fix the cause, and retest.

## Define the test before interacting

Establish these inputs from the request and project before changing device state:

- target platform: `android` or `ios`
- Android package name or iOS bundle ID
- starting app state and any required test data
- exact actions that reproduce the flow
- an observable success marker and failure marker

Infer them from the project when reliable. Ask for the missing value when it
cannot be determined. Do not treat a successful tap or exit code as proof that
the feature works.

## Prepare the app and automation server

Confirm the installed CLI and available target:

```bash
visiontest --version
visiontest available_device -p <platform> --json
```

Build and install the app under test with the project's own toolchain. VisionTest
installs only its Android automation APKs:

```bash
visiontest install_automation_server -p android
visiontest start_automation_server -p <platform>
visiontest automation_server_status -p <platform>
visiontest launch_app -p <platform> <package-or-bundle-id>
```

`install_automation_server` is Android-only and normally runs once per
automation-server version. iOS requires a booted simulator and a current
XCUITest bundle.

Upgrade both Android automation APKs or the iOS XCUITest bundle before using
`press_key`, `clear_text`, `long_press`, `double_tap`, `dismiss_keyboard`,
`handle_alert`, or targeted `input_text`. Updating only the CLI/MCP JAR does
not update native methods. Restart the automation server after upgrading.

## Evidence-driven automation loop

1. Capture the initial screen with `screenshot`.
2. Read `get_interactive_elements --json` and identify stable selectors and
   current coordinates. On iOS, use `--bundle-id <bundle-id>` to scope
   inspection to the target app.
3. Prefer selectors for taps, long press, double tap, and targeted input when
   stable element identity matters. Use coordinates when the location itself is
   intentional. Reproduce the user flow and handle its keyboard or alert steps
   before checking the outcome.
4. After every navigation or layout change, inspect elements again before using
   coordinates.
5. Wait for the defined success marker. For transient UI, wait for disappearance
   with `wait_for_element --gone`.
6. Capture the final screenshot and, when useful, the full UI hierarchy.
7. Compare the observed state with the expected outcome and retain the commands,
   timestamps, screenshots, structured output, and relevant logs.
8. Stop the automation server when the session is finished.

Prefer `wait_for_element` to fixed sleeps. Use `find_element --json` for a
single assertion and `get_interactive_elements --json` when choosing the next
action.

## Selectors

`find_element`, `wait_for_element`, `swipe_on_element`, and `tap_on_element`
accept:

- `--text`
- `--text-contains`
- `--resource-id`
- `--class-name`
- `--content-description`
- `--bundle-id` to scope an iOS app

`find_element`, `wait_for_element`, `swipe_on_element`, and `tap_on_element`
require at least one element selector. `--bundle-id` scopes iOS lookup and taps
but does not satisfy that requirement by itself; Android rejects it. In Flutter
apps, visible labels commonly appear as `contentDescription`, so inspect that
field when `text` is empty.

`long_press` and `double_tap` accept the same selectors or a complete
nonnegative integer `--x X --y Y` pair. Supply exactly one target form. An iOS
`--bundle-id` must be nonblank and does not count as a selector. Android rejects
app scope.

`input_text` keeps its required positional text value and accepts optional
`--target-text`, `--target-text-contains`, `--target-resource-id`,
`--target-class-name`, and `--target-content-description`. On iOS these match
text, partial text, accessibility identifier, element type, and accessibility
label. MCP and native JSON-RPC use `targetText`, `targetTextContains`,
`targetResourceId`, `targetClassName`, and `targetContentDescription`.
Without target selectors, input types into the focused element as before.

Selector gestures and targeted input poll natively every 500 ms. Their timeout
defaults to 10,000 ms and accepts 1 through 30,000 ms via `--timeout` or MCP
`timeoutMs`. Android targets must be visible and enabled; iOS targets must
exist, be enabled, and be hittable. Text targets must also be editable. Lookup,
tap, focus confirmation, and input share one timeout budget in one native
request. Coordinate gestures and input without selectors reject a timeout.

## Forms and permission prompts

For an Android field that needs replacing, focus it with empty targeted input,
clear it, enter the new value, and submit with the keyboard:

```bash
visiontest input_text -p android "" --target-resource-id "com.example:id/search"
visiontest clear_text -p android
visiontest input_text -p android "coffee" --target-resource-id "com.example:id/search"
visiontest press_key -p android enter
visiontest wait_for_element -p android --text "Search results"
```

`clear_text` works only on the focused editable Android element and accepts no
selectors. `press_key` accepts a nonnegative Android key code or `enter`, `tab`,
`backspace`, `delete`, or `escape`. In MCP, `android_press_key` requires exactly
one of `keyCode` or `action`. Backspace deletes before the cursor; delete removes
text after it.

On iOS, inspect the alert and choose its exact button label when the choice
matters:

```bash
visiontest handle_alert -p ios accept --bundle-id com.example.app --button-label "Allow"
```

`handle_alert` searches the scoped or active app first, then SpringBoard for
system permission prompts. Without a label, `accept` chooses the last enabled,
hittable button and `dismiss` the first. The result identifies the tapped
button. Verify the expected app state after handling the prompt.

Use `dismiss_keyboard -p ios --bundle-id com.example.app` when the next control
requires hiding a visible software keyboard. It swipes downward on the keyboard
and verifies disappearance without submitting the field. No keyboard or a
keyboard that remains visible returns an operation failure.

## Command reference

The CLI has 29 commands. The 28 device commands below require `-p android` or
`-p ios`. The remaining command, `init --agent claude,opencode,codex`, installs
project-local agent instructions without a platform. Root `--help` and
`--version` also need no platform.

| Command | Purpose |
| --- | --- |
| `install_automation_server` | Install Android automation APKs |
| `start_automation_server` | Start Android or iOS automation |
| `stop_automation_server` | Stop automation; safe when already stopped |
| `automation_server_status` | Check server reachability |
| `available_device [--json]` | Describe the first available target |
| `get_interactive_elements [--include-disabled] [--bundle-id] [--json]` | List actionable elements and center coordinates; iOS app scope is optional |
| `find_element [selectors] [--json]` | Find one element |
| `wait_for_element [selectors] [--timeout MS] [--gone]` | Wait up to 30000 ms for appearance or disappearance |
| `get_ui_hierarchy [--bundle-id]` | Return the full UI hierarchy as XML; iOS app scope is optional |
| `get_device_info [--json]` | Return display and OS metadata |
| `screenshot [--output PATH]` | Save a PNG on the host |
| `tap_by_coordinates <x> <y>` | Tap integer screen coordinates |
| `tap_on_element [selectors] [--timeout MS]` | Directly tap a selected actionable element; iOS also accepts `--bundle-id` |
| `input_text <text> [target selectors] [--timeout MS] [--bundle-id]` | Type into the focused field or select, focus, and type; iOS app scope is optional |
| `press_key <key>` | Press an Android key code or named action |
| `clear_text` | Clear the focused editable Android field |
| `long_press [selectors or --x X --y Y] [--timeout MS]` | Hold the target for 800 ms; iOS also accepts `--bundle-id`; timeout requires selectors |
| `double_tap [selectors or --x X --y Y] [--timeout MS]` | Double-tap the target; iOS also accepts `--bundle-id`; timeout requires selectors |
| `dismiss_keyboard [--bundle-id]` | Dismiss a visible iOS software keyboard and verify disappearance |
| `handle_alert <accept\|dismiss> [--button-label LABEL] [--bundle-id]` | Handle an iOS app or system alert |
| `swipe_direction <direction> [--distance VALUE] [--speed VALUE]` | Swipe across the screen |
| `swipe <startX> <startY> <endX> <endY> [--steps N]` | Swipe between coordinates |
| `swipe_on_element <direction> [selectors] [--speed VALUE]` | Swipe inside a matched element |
| `press_back` | Press Android back |
| `press_home` | Press Android or iOS home |
| `launch_app <id>` | Launch an Android package or iOS bundle |
| `list_apps [--json]` | List installed app IDs |
| `info_app <id> [--json]` | Inspect an installed app |

Directions are `up`, `down`, `left`, or `right`. Distance values are
`short`, `medium`, or `long`; speed values are `slow`, `normal`, or
`fast`. Coordinate swipe steps must be positive and default to 20.

`tap_on_element` waits natively at 500 ms intervals for a matching actionable
element, defaults to a 10,000 ms timeout, and accepts at most 30,000 ms. It
does not auto-scroll. It has no `--json` mode: inspect returned text and error
status. Update both Android automation APKs or the iOS automation bundle before
using it with a newly installed CLI/MCP JAR, because older native servers do not
implement `ui.tapOnElement`.

## Structured results and failures

`--json` is available on `available_device`, `get_interactive_elements`,
`get_device_info`, `find_element`, `list_apps`, and `info_app`. It emits
one JSON object without the JSON-RPC envelope.

Inspect `found`, `success`, and `error` fields for structured commands and the
returned text for interactions. A normally returned operation can exit 0 while
reporting `found: false`, `success: false`, or a text failure. Missing targets,
blocked or noneditable elements, focus failure, and native interaction errors
have distinct failure results. Do not treat exit 0 as proof of success.

| Exit | Meaning | Response |
| --- | --- | --- |
| 0 | Command returned normally | Inspect outcome fields or returned text |
| 1 | Unexpected or generic thrown failure | Read stderr and captured state; retry only after changing a relevant condition |
| 2 | Invalid arguments | Correct the command |
| 3 | Server unreachable | Start the platform server, then retry once |
| 4 | Device unavailable | Connect Android or boot an iOS simulator |
| 5 | Unsupported platform | Use a platform supported by the command |

## Root-cause evidence

Start log capture before reproducing the failing action and keep timestamps
aligned with screenshots and commands. Use the project's platform tooling, such
as filtered `adb logcat` output on Android or the Xcode/simulator log stream on
iOS. Narrow logs to the application or subsystem when possible. Set
`VISION_TEST_LOG_LEVEL=DEBUG` to include full stack traces from Kotlin MCP tool
error handling. Read `adb logcat` or Xcode/simulator output for native
automation-server diagnostics.

Use the evidence to separate app failures from automation failures:

- UI changes but the expected marker never appears: inspect application state,
  validation, navigation, and network logs.
- The UI does not react and VisionTest returns `success: false`: refresh the
  hierarchy and selectors, then inspect automation-server logs.
- A wait times out: retain the final screenshot and hierarchy; the timeout is
  evidence only when the expected condition and starting state were defined.

After a fix, repeat the same actions and assertions from the same starting state.
