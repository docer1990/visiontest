# VisionTest mobile testing

Use VisionTest to inspect and control Android apps or iOS simulators while
testing a code change. Work from a concrete user flow and its visible result.

## Choose the available interface

Use the VisionTest CLI by default, even when MCP tools are available. Use MCP
only when explicitly requested. Troubleshoot CLI failures and report remaining
blockers instead of switching to MCP automatically.

Check `visiontest --version` once per session. Use `visiontest --help` to discover
commands and `visiontest <command> --help` only when its syntax is unknown.
Reuse that information until the installed version changes. Device commands
require `-p android` or `-p ios`. Running `visiontest` without arguments starts
the MCP stdio server; it is not a CLI availability check.

### Using MCP only when explicitly requested

Read exposed tool names and schemas. Platform prefixes are not uniform;
Android uses `get_interactive_elements` but `android_input_text`. Pass schema
JSON arguments, not CLI flags. Element waits use `resourceId`, `bundleId`, and
`timeoutMs`; disappearance uses `wait_until_gone` or `ios_wait_until_gone`.
CLI `--json` is not an MCP argument. Check the MCP error status and content,
including `error`, `result.found`, or `result.success` in returned JSON-RPC text.
A normally returned tool result can still describe an operation failure.

## Prepare the host

For CLI use, if missing from PATH, check `~/.local/bin/visiontest` before installing.
If absent, use the project's installation guide or the release installer:

```bash
curl -fsSL https://github.com/docer1990/visiontest/releases/latest/download/install.sh | bash
```

Installer PATH changes do not update the calling shell. Use
`~/.local/bin/visiontest --version` to verify installation and use that path
until PATH is updated. Installing the CLI or running `init` does not connect MCP.

Use Java 17+ on both platforms. Android requires Platform Tools (`adb`) and an
authorized device/emulator; iOS requires macOS, full Xcode, and a compatible
simulator runtime. Prebuilt iOS
bundles are available only on macOS arm64; for a source build set
`VISION_TEST_IOS_PROJECT_PATH` to the current checkout's Xcode project.
Build and install the app with its own toolchain. Reuse a build only when it
contains the code under test; testing an old installation does not validate a fix.

## Define and run the test

Before changing device state, identify the platform, app ID, starting state,
test data, actions, and expected markers. Infer reliable values from the project;
ask only for missing information that makes the test ambiguous.

Use a short sequence of actions and assertions for the requested behavior.
For a targeted fix, reproduce it and verify the affected flow; expand testing
when related changes or failures warrant it, or when the user requests a suite.
Choose a specific final marker or value that distinguishes success from failure.
Check intermediate states only when needed to choose the next action, preserve
test isolation, or cover a separate requirement. Stop when the requested
assertions have been evaluated; do not keep exploring a passing flow.

Check the target once with `available_device --json` and the platform option.
There is no CLI device-selection option. If the first target is unsuitable,
ask the user to select or isolate the intended device before changing its state.

Check `automation_server_status` at session setup, start only if needed, and
remember ownership. Reuse the server, app ID, device metadata, and known selectors
across tests. Recheck setup after a target change, restart, disconnect, or relevant
failure, not before every action. Group scenarios with compatible setup, while
restoring any app state required for independent assertions. Serialize actions
on the same device; parallel UI commands can invalidate each other's state.

### Choose the smallest sufficient operation

Use structured queries for functional checks. Commands in this table omit
`visiontest` and the required platform option.

| Need | Operation |
| --- | --- |
| Check one known element now | `find_element --resource-id ID --json` |
| Await an expected result | `wait_for_element --text "Expected marker"`; add `--gone` for disappearance |
| Tap a known control | `tap_on_element --resource-id ID`; it already waits for an actionable target |
| Fill a known field | `input_text "value" --target-resource-id ID`; it waits, focuses, and enters text |
| Discover controls on an unfamiliar screen | `get_interactive_elements --json` |
| Reveal content in a known list or carousel | `swipe_on_element up --resource-id CONTAINER_ID` |
| Scroll the main screen without a container selector | `swipe_direction up --distance medium` |
| Inspect structure missing from the filtered list | `get_ui_hierarchy` |
| Check appearance, inaccessible content, or requested visual evidence | `screenshot --output ./evidence.png`, then open the image |

Do not precede every tap with a find or wait, or repeat a successful wait's lookup.
Use targeted queries for expected markers; refresh the element list when a
selector is unknown or fails, or the observed state differs from expectations.
Use one bounded wait instead of manual polling or sleeps. Its timeout is a
maximum, not a mandatory delay; lowering it needlessly can create retries.
A successful interaction alone does not prove the expected app outcome.

`--json` produces compact JSON without JSON-RPC framing. The element list omits
uninformative containers and excludes disabled elements by default; request
`--include-disabled` only when needed. On iOS, scope queries with `--bundle-id`.
There are no native `--compact`, `--fields`, or `--limit` options.

If a large element list is needed only to choose selectors and `jq` is available,
reduce fields before the output reaches the model:

```bash
set -o pipefail
visiontest get_interactive_elements -p android --json | jq -c '
  if .success == true then .elements |= map(
    {resourceId,text,contentDescription,className,value,isEnabled}
    | with_entries(select(.value != null and .value != "")))
  else . end'
```

This retains all elements and outcome/error fields but omits geometry and
interaction flags. Keep those fields when needed. Filtering reduces model input,
not native work or CLI startup. Do not truncate with `head` or suppress errors.

These examples show first use. Skip completed setup and known-selector discovery.
Adapt app IDs, selectors, and markers; check each result before continuing.

### Android CLI example

Install the APK pair before first use and after a server upgrade.

```bash
visiontest available_device -p android --json
visiontest install_automation_server -p android
visiontest start_automation_server -p android
visiontest automation_server_status -p android
visiontest launch_app -p android com.example.app
visiontest get_interactive_elements -p android --json
visiontest tap_on_element -p android --resource-id com.example.app:id/login
visiontest wait_for_element -p android --text "Home" --timeout 10000
```

### iOS CLI example

`install_automation_server` is Android-only (exit 5 on iOS). Use the host-installed
XCUITest bundle or configured source project for iOS.

If needed, select a simulator from `xcrun simctl list devices available`, run
`xcrun simctl boot <UDID>` and `xcrun simctl bootstatus <UDID> -b` before server
startup. Install a suitable runtime in Xcode if none is available.

```bash
visiontest available_device -p ios --json
visiontest start_automation_server -p ios
visiontest automation_server_status -p ios
visiontest launch_app -p ios com.example.app
visiontest get_interactive_elements -p ios --bundle-id com.example.app --json
visiontest tap_on_element -p ios --bundle-id com.example.app --resource-id login
visiontest wait_for_element -p ios --bundle-id com.example.app --text "Home" --timeout 10000
```

### Choose targets and verify the result

Prefer `tap_on_element` for a uniquely identified control, using a stable
resource ID or accessibility identifier over coordinates. It does not scroll the screen.
On Flutter, visible labels often appear in `contentDescription`.

### Reveal an offscreen target

When accessibility data identifies the target and scrollable region, scroll
without a preliminary screenshot. Tree presence or `found: true` does not prove
visibility or actionability. Android filters by visibility and screen bounds;
iOS can include offscreen elements and does not report hittability. Absence
from this filtered list does not prove absence from the app.

Target the visible container with `swipe_on_element`, not the offscreen child.
It resolves and swipes the container in one native operation. For the main screen
without a container selector, `swipe_direction` calculates coordinates. Directions
describe finger motion: `up` reveals content below, `down` content above, and
`left`/`right` horizontal content. Use coordinate `swipe` only when necessary.

For example, reveal a lower row in an identified iOS list:

```bash
visiontest swipe_on_element -p ios up --resource-id settings-list --bundle-id com.example.app
visiontest find_element -p ios --resource-id notifications --bundle-id com.example.app --json
```

Assess progress from current contents and `bounds`; retain Android `isScrollable`
when identifying containers. The optional `jq` projection omits these fields.
Each command performs one gesture, not scroll-until-found; `swipe_on_element`
has no `--timeout`. Bound attempts and stop on failure or unchanged contents and
bounds. Inspect an unknown container/direction; use screenshots only if needed
to resolve ambiguity. Swipe success alone does not prove the target was revealed.

### Enter text and handle platform controls

Android targeted input focuses and sets the field's value. Empty input clears
it; it is not focus-only. Verify the field directly when its value is itself
under test; otherwise a specific downstream result can verify the input flow.

```bash
visiontest input_text -p android "coffee" --target-resource-id "com.example:id/search"
visiontest press_key -p android enter
visiontest wait_for_element -p android --text "Results for coffee"
```

`clear_text` clears only the focused Android field. iOS targeted input types
without promising replacement; there is no generic iOS `clear_text` command.
Verify any claimed replacement. For iOS alerts use `handle_alert accept|dismiss`
with exact `--button-label` when the choice matters, and `dismiss_keyboard` when
the keyboard blocks a control. `press_key` and `press_back` are Android-only;
use the app's visible submit/back control on iOS.

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
They print one JSON object without JSON-RPC framing. Check for a top-level
`error` first, then `found` or `success` when present. For example,
`{"found":false}` means the lookup succeeded but the target is absent;
`{"error":{"code":-32601,"message":"Unknown method"}}` is an operation failure.
Both can exit 0. Interaction commands do not support `--json`; their text can
include JSON-RPC content. Check that content as well as the exit status.
Exit codes 1 through 5 mean generic failure, invalid arguments,
server unreachable, device unavailable, and unsupported platform respectively.

Capture screenshots for visual assertions, content missing from the accessible
hierarchy, unresolved ambiguity, or evidence requested by the user. Do not take
routine before/after screenshots for functional tests. Open any captured PNG
with the session's image-viewing tool before making a visual claim; a saved path
is not verification. Element presence alone cannot prove layout or appearance.

## Recover only when there is new evidence

After an interaction timeout, inspect the expected result before retrying;
the action may already have happened. If a target is missing, inspect its state
or container and retry only after correcting the cause. Do not repeat identical
failed commands or cycle through screenshots, restarts, and longer timeouts
without evidence. Stop a scenario at a failed prerequisite; downstream steps
are blocked, not additional app failures. Continue independent scenarios when
their starting state can be restored.

For connection failures, check device and server status before restarting.
Method-not-found usually means a native/JAR version mismatch: update the Android
APK pair or iOS bundle/source build, then restart. Capture filtered Android
`adb logcat` or iOS Xcode/simulator logs only when needed to explain a failure.

After a fix, repeat the failed flow and affected assertions. Keep the server
running across the requested batch, including individual failures. At task end,
stop only a server you started with `visiontest stop_automation_server -p android`
or `-p ios`,
unless asked to leave it running. Use MCP cleanup only if the user selected MCP.
Report the platform/app, checks, expected versus observed results, and relevant
evidence paths concisely. Distinguish passed, failed, and blocked checks; avoid
repeating raw outputs already inspected.

## Measure host performance when requested

Enable tracing explicitly only for performance measurement. For CLI measurement,
put the root option before the command, for example:

```bash
visiontest --trace-performance ./traces/android.jsonl get_device_info -p android --json
```

For explicitly requested MCP measurement, set `VISIONTEST_TRACE_PERFORMANCE`
in the server environment before startup; CLI ignores it. Use a separate local
JSONL path per concurrent process. Tracing preserves operation results and output.
Treat unavailable/incomplete trace warnings, dropped records, or missing shutdown
as measurement gaps. Check `session.end`, zero drops, matched invocation counts,
and successful shutdown before treating a session as complete.

The file contains session records and completed host spans. Check the terminal
`session.end`, zero drops, matched invocation counts, and successful shutdown before
using a session as complete; a footer alone cannot prove cleanup. The queue holds
4096 records and shutdown waits at most 1,000 ms. Force kills, overflow, and writer
failures can leave incomplete data. Do not infer success from a normal return;
`operationOutcome` may be `failure` or `unknown`. Inclusive durations overlap, so
do not sum parents and children. CLI preparation includes parsing and validation,
not device work, and early parser errors may be untraced. Native timings may be
unavailable from older installed Android APKs or iOS bundles. Such servers continue
to run existing commands; the trace records `status: missing` and VisionTest does
not repeat the operation to retrieve timing data. Install a current Android APK
pair or update/rebuild the iOS bundle to collect native spans. Missing stages mean
unavailable or unfinished work, never zero work. Android cannot isolate request
queue delay or all UIAutomator synchronization. iOS cannot expose all XCTest
scheduling or SDK synchronization, and freezes its timing snapshot at the HTTP
completion boundary. Body byte counts are not tokens or agent image usage.
Ordinary test execution uses the interface guidance above.

Compare the same test flow using agent token usage and elapsed time to assess
savings; do not infer them from smaller output alone.

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
