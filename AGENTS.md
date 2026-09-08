# VisionTest Mobile Automation

VisionTest gives coding agents eyes and hands on Android devices and iOS
simulators. Use it to verify a recent implementation end to end, reproduce a
reported bug, capture UI evidence after each action, and correlate that evidence
with the build, application, and device logs available in the development
environment.

Device-operation commands require `--platform android` or `--platform ios`
(alias `-p`); `init`, root `--help`, and root `--version` do not. The CLI
reuses the same backend as the MCP server tools.

## Standard Automation Loop

```
1. Start the server      → visiontest start_automation_server -p <platform>
2. Capture initial state → visiontest screenshot -p <platform>
3. Inspect elements      → visiontest get_interactive_elements -p <platform>
4. Reproduce the flow    → tap, swipe, input_text, and wait_for_element
5. Capture final state   → screenshot, find_element, or get_ui_hierarchy
6. Diagnose and repeat   → correlate UI evidence with available logs, then retest
```

## Commands

### Setup
| Command | Platforms | Description |
|---------|-----------|-------------|
| `install_automation_server` | android | Install automation APKs on device |
| `start_automation_server` | android, ios | Start the automation server |
| `stop_automation_server` | android, ios | Stop the automation server (idempotent, exit 0 if already stopped) |
| `automation_server_status` | android, ios | Check if server is running |

### Inspection
| Command | Platforms | Description |
|---------|-----------|-------------|
| `get_interactive_elements [--include-disabled] [--json]` | android, ios | List tappable elements with coordinates |
| `find_element [selectors] [--json]` | android, ios | Find an element using the selectors described below |
| `available_device [--json]` | android, ios | Describe the first available device or simulator |
| `get_ui_hierarchy` | android, ios | Full UI tree as XML |
| `get_device_info [--json]` | android, ios | Display size, rotation, SDK/iOS version |
| `screenshot [--output PATH]` | android, ios | Save PNG (default: `./screenshots/`) |
| `wait_for_element [selectors] [--timeout MS] [--gone]` | android, ios | Poll until an element appears (or disappears with `--gone`). Selectors: `--text`, `--text-contains`, `--resource-id`, `--class-name`, `--content-description`, `--bundle-id` (iOS). Timeout max 30000ms. Exit 1 on timeout |

### Interaction
| Command | Platforms | Description |
|---------|-----------|-------------|
| `tap_by_coordinates <x> <y>` | android, ios | Tap at screen coordinates |
| `input_text <text>` | android, ios | Type into focused element |
| `swipe_direction <up\|down\|left\|right> [--distance short\|medium\|long] [--speed slow\|normal\|fast]` | android, ios | Swipe gesture |
| `swipe <startX> <startY> <endX> <endY> [--steps N]` | android, ios | Swipe between integer coordinates; positive steps, default 20 |
| `swipe_on_element <up\|down\|left\|right> [selectors] [--speed slow\|normal\|fast]` | android, ios | Swipe inside the matched element; default speed normal |

### Navigation
| Command | Platforms | Description |
|---------|-----------|-------------|
| `press_back` | android | Press back button |
| `press_home` | android, ios | Press home button |

### Apps
| Command | Platforms | Description |
|---------|-----------|-------------|
| `launch_app <id>` | android, ios | Launch by package name or bundle ID |
| `list_apps [--json]` | android, ios | List installed package names or bundle IDs |
| `info_app <id> [--json]` | android, ios | Get app information by package name or bundle ID |

### Project Setup
| Command | Platforms | Description |
|---------|-----------|-------------|
| `init --agent <claude,opencode,codex>` | none | Install or refresh project-local VisionTest skill files for the selected agents |

## The `--platform` Flag

Device-operation commands require `--platform` (or `-p`). There is no default and no auto-detection. The `init` command and root `--help` and `--version` options do not accept or require a platform. Android-only commands (`install_automation_server`, `press_back`) reject `--platform ios`.

## Selectors and JSON

`find_element` and `swipe_on_element` require at least one of `--text`,
`--text-contains`, `--resource-id`, `--class-name`, or `--content-description`.
On iOS these map to text, partial text, accessibility identifier, element type,
and accessibility label. `--bundle-id` optionally scopes the iOS app; it is not
an element selector and is rejected on Android for these two commands.

`--json` is available on `get_interactive_elements`, `get_device_info`,
`find_element`, `list_apps`, `info_app`, and `available_device`. It emits one JSON
object on stdout. UI results omit JSON-RPC framing; app lists use
`{"apps":["com.example.app"]}`. Default output remains unchanged. Check `found`
or `success` in UI results: operation failures can still exit 0. Thrown failures
use stderr and the exit codes below. JSON schemas: `docs/cli-json.md` in the
VisionTest repository.

The iOS element swipe requires an automation bundle containing
`ui.swipeOnElement`; update or rebuild an older installed bundle.

## Exit Codes

| Code | Meaning | What to do |
|------|---------|------------|
| 0 | Success | Continue |
| 1 | Generic failure | Read stderr, retry or escalate |
| 2 | Usage error | Fix the command arguments |
| 3 | Server not reachable | Run `start_automation_server` first |
| 4 | Device not found | Connect a device or boot a simulator |
| 5 | Platform not supported | Use the correct `--platform` value |

## Flutter Apps

In Flutter apps, text labels appear in `content-desc` (contentDescription) rather than `text`. If `get_interactive_elements` returns elements without visible text, look at the `contentDescription` field instead.

## Example Session

```bash
# Android workflow
visiontest install_automation_server -p android
visiontest start_automation_server -p android
visiontest screenshot -p android
visiontest get_interactive_elements -p android
visiontest tap_by_coordinates -p android 540 1200
visiontest wait_for_element -p android --text "Welcome" --timeout 5000
visiontest input_text -p android "hello"
visiontest screenshot -p android --output ./after.png
visiontest stop_automation_server -p android

# iOS workflow
visiontest start_automation_server -p ios
visiontest screenshot -p ios
visiontest get_interactive_elements -p ios
visiontest tap_by_coordinates -p ios 200 400
```
