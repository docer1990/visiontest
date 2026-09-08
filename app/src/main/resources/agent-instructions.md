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

## Evidence-driven automation loop

1. Capture the initial screen with `screenshot`.
2. Read `get_interactive_elements --json` and identify stable selectors and
   current coordinates.
3. Reproduce the user flow with taps, swipes, text input, and explicit waits.
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

`find_element`, `wait_for_element`, and `swipe_on_element` accept:

- `--text`
- `--text-contains`
- `--resource-id`
- `--class-name`
- `--content-description`
- `--bundle-id` to scope an iOS app

`find_element`, `wait_for_element`, and `swipe_on_element` require at least
one element selector. `--bundle-id` scopes iOS lookup but does not satisfy that
requirement by itself; Android rejects it. In Flutter apps, visible labels commonly appear as
`contentDescription`, so inspect that field when `text` is empty.

## Command reference

Every device command below requires `-p android` or `-p ios`.

| Command | Purpose |
| --- | --- |
| `install_automation_server` | Install Android automation APKs |
| `start_automation_server` | Start Android or iOS automation |
| `stop_automation_server` | Stop automation; safe when already stopped |
| `automation_server_status` | Check server reachability |
| `available_device [--json]` | Describe the first available target |
| `get_interactive_elements [--include-disabled] [--json]` | List actionable elements and center coordinates |
| `find_element [selectors] [--json]` | Find one element |
| `wait_for_element [selectors] [--timeout MS] [--gone]` | Wait up to 30000 ms for appearance or disappearance |
| `get_ui_hierarchy` | Return the full UI hierarchy as XML |
| `get_device_info [--json]` | Return display and OS metadata |
| `screenshot [--output PATH]` | Save a PNG on the host |
| `tap_by_coordinates <x> <y>` | Tap integer screen coordinates |
| `input_text <text>` | Type into the focused element |
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

## Structured results and failures

`--json` is available on `available_device`, `get_interactive_elements`,
`get_device_info`, `find_element`, `list_apps`, and `info_app`. It emits
one JSON object without the JSON-RPC envelope.

Inspect `found`, `success`, and `error` fields. A normally returned
operation can exit 0 while reporting `found: false` or `success: false`.

| Exit | Meaning | Response |
| --- | --- | --- |
| 0 | Command returned normally | Inspect structured outcome fields |
| 1 | Operation or wait failed | Read stderr and captured state; retry only after changing a relevant condition |
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
