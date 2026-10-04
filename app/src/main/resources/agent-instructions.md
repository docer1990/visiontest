# VisionTest mobile testing

Use VisionTest to inspect and control Android apps or iOS simulators while
testing a code change. Work from a concrete user flow and its visible result.

## Choose the available interface

When MCP tools are available, inspect their current names and input schemas,
then use those tools directly. Trust the returned content and error status; some
operation failures arrive as normal tool results. When the CLI is available, run
`visiontest --help` for syntax and `visiontest --version` for the installed
version. Device commands require `-p android` or `-p ios`.

If the CLI is missing and MCP is unavailable, install VisionTest using the
project's installation guide or the official release installer:

```bash
curl -fsSL https://github.com/docer1990/visiontest/releases/latest/download/install.sh | bash
```

The installer needs Java 17 or newer. iOS automation requires macOS, full Xcode,
and a compatible simulator runtime. The prebuilt XCUITest bundle is included
only on macOS arm64. On other supported hosts, use a current source checkout and
set `VISION_TEST_IOS_PROJECT_PATH` to its Xcode project when building from source.
The app under test must be built and installed with that app project's toolchain.

## Define and run the test

Before changing device state, identify the platform, app package or bundle ID,
starting state, test data, reproduction actions, and visible success and failure
markers. Infer them from the project when reliable; ask when a missing value
would make the test ambiguous.

Check the target with `available_device`. It describes the first available
device; VisionTest currently has no CLI option to select another attached device.
Ask the user to select or make the intended device the only available target
before changing device state when the first one is unsuitable.

Start the automation server, check its status, and launch the app:

```bash
visiontest start_automation_server -p android
visiontest automation_server_status -p android
visiontest launch_app -p android com.example.app
```

Use `-p ios` and the app's bundle ID for iOS. Install the Android server APK
pair with `install_automation_server -p android` after a new server release.
For iOS, restart the installer to refresh its prebuilt XCUITest bundle, or build
from the configured source project. A method-not-found error from a native
server usually indicates a version mismatch with the JAR. Upgrade the native
APK or bundle and restart the server before retrying.

If no iOS simulator is booted, list available simulators with
`xcrun simctl list devices available`, boot the intended simulator with
`xcrun simctl boot <UDID>`, then wait for it with
`xcrun simctl bootstatus <UDID> -b` before starting the iOS server. If there is
no suitable simulator, create or install a simulator runtime in Xcode first.

Capture and open an initial screenshot. Inspect
`get_interactive_elements --json`, then choose a stable selector for the
intended control. On iOS, add `--bundle-id` to scope inspection and lookup to
the app. On Flutter, visible labels often appear in `contentDescription`.
Refresh the hierarchy and selectors after navigation or any layout change.

Prefer `tap_on_element` for a uniquely identified control and
`wait_for_element` for a visible success marker. It does not scroll the screen.
If lookup says `found: false`, refresh the inspection, scroll with a deliberate
gesture, inspect again, and retry only after the target appears. Bound repeated
scrolling and stop when the screen no longer changes. Use `--gone` to wait for a
transient element to disappear. Do not use a successful command or tap as the
assertion: inspect the result fields and compare the observed screen or state
with the expected marker.

For an Android field that must be replaced, inspect it, focus it with empty
targeted input, clear it, then enter the new value:

```bash
visiontest input_text -p android "" --target-resource-id "com.example:id/search"
visiontest clear_text -p android
visiontest input_text -p android "coffee" --target-resource-id "com.example:id/search"
visiontest press_key -p android enter
visiontest wait_for_element -p android --text "Search results"
```

`clear_text` only clears the focused Android field. iOS targeted `input_text`
types into the selected field but does not promise to replace its current value;
there is no generic iOS `clear_text` command. Do not claim an iOS value was
replaced unless you observe that result. On iOS, use `handle_alert accept` or
`dismiss` and an exact `--button-label` when the choice matters. Use
`dismiss_keyboard` when a visible iOS keyboard blocks the next control.

## Selectors and outcomes

Element lookup, waits, taps, element swipes, and selector gestures accept
`--text`, `--text-contains`, `--resource-id`, `--class-name`, and
`--content-description`. iOS maps these to label/text, partial label/value,
accessibility identifier, element type, and accessibility label as implemented
by the operation. iOS lookup tests selectors in a fixed priority order; multiple
selectors are not combined into an AND query. Use one distinctive selector when
possible. `--bundle-id` only scopes an iOS app and never replaces an element
selector; Android rejects it.

`long_press` and `double_tap` accept exactly one target: selectors or a complete
nonnegative `--x X --y Y` pair. `input_text` requires its text argument and can
optionally target a field with `--target-*` selectors. Selector gestures and
targeted input wait natively, poll every 500 ms, default to 10,000 ms, and accept
timeouts from 1 through 30,000 ms. Android targets must be visible and enabled;
iOS targets must exist, be enabled, and be hittable. Text targets must be
editable. These interactions do not auto-scroll.

The six CLI inspection commands `available_device`, `get_interactive_elements`,
`get_device_info`, `find_element`, `list_apps`, and `info_app` support `--json`.
They print one JSON object without JSON-RPC framing. Check `found`, `success`,
and `error`: an operation may return exit 0 with `found: false` or `success:
false`. Other interaction commands return text; check both that result and its
error status. Exit codes 1 through 5 mean generic failure, invalid arguments,
server unreachable, device unavailable, and unsupported platform respectively.

When a command saves a screenshot, open the PNG with an image-viewing tool
available in the current agent session. Compare the image with the expected
state or reference before making a visual claim. A saved path alone is not visual
verification. Keep useful screenshots and relevant logs with the reproduction.

Capture Android logs with filtered `adb logcat` and iOS logs with the Xcode or
simulator log stream when the UI and expected marker disagree. Repeat the same
actions and assertions after a fix, then stop the automation server when done.

## Refresh these instructions

`visiontest init` is separate from installing or upgrading the CLI. In each
project, run this command again to replace the generated skill with the version
embedded in the installed JAR:

```bash
visiontest init --agent claude,opencode,codex
```

It overwrites each selected `SKILL.md` completely and does not merge local
edits. The generated file states which VisionTest version created it. Check the
agent-specific skill path and reinstall only the agents used by the project:
Claude uses `.claude/skills/visiontest/SKILL.md`, OpenCode uses
`.opencode/skills/visiontest/SKILL.md`, and Codex uses
`.agents/skills/visiontest/SKILL.md`.
