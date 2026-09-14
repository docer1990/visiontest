# VisionTest

VisionTest is mobile automation built for agentic software development. It gives
any coding agent eyes and hands on Android devices and iOS simulators, so the
agent can close its own feedback loop: test a change end to end, reproduce a
reported bug, observe the UI after each action, and correlate that behavior with
build, application, and device logs to find the root cause.

One MCP server and CLI expose UI inspection, element lookup, taps, swipes,
targeted text input, keyboard actions, iOS alert handling, screenshots, and app
management. This lets an agent move from implementation to evidence without
handing the device back to a human.

## Requirements

- JDK 17 or newer
- macOS or Linux, on arm64 or x86_64
- Android Platform Tools for Android automation
- macOS, full Xcode, and a compatible simulator runtime for iOS automation

## Quick start

Install the latest release:

```bash
curl -fsSL https://github.com/docer1990/visiontest/releases/latest/download/install.sh | bash
```

The installer verifies every download, installs the JAR and Android APKs, and
adds `visiontest` under `~/.local/bin`. On macOS arm64 it also installs the
prebuilt iOS test bundle. Re-run the command to update.

Connect VisionTest to an MCP client:

```bash
# Claude Code
claude mcp add visiontest java -- -jar ~/.local/share/visiontest/visiontest.jar

# OpenAI Codex CLI
codex mcp add visiontest -- java -jar ~/.local/share/visiontest/visiontest.jar
```

<details>
<summary>Configuration for Claude Desktop, Copilot CLI, and OpenCode</summary>

JSON configuration files require an absolute JAR path; they do not expand `~`.

Claude Desktop uses `~/Library/Application Support/Claude/claude_desktop_config.json`
on macOS or `~/.config/Claude/claude_desktop_config.json` on Linux:

```json
{
  "mcpServers": {
    "visiontest": {
      "command": "java",
      "args": ["-jar", "/ABSOLUTE/PATH/TO/.local/share/visiontest/visiontest.jar"]
    }
  }
}
```

GitHub Copilot CLI uses `~/.copilot/mcp-config.json` and the same server object
with `"type": "stdio"`. OpenCode uses this entry in a project or user
`opencode.json`:

```json
{
  "mcp": {
    "visiontest": {
      "type": "local",
      "command": ["java", "-jar", "/ABSOLUTE/PATH/TO/.local/share/visiontest/visiontest.jar"]
    }
  }
}
```

</details>

See the [installation guide](docs/installation.md) for custom paths, artifacts,
checksums, and the iOS source fallback.

## Agentic workflow

MCP clients discover VisionTest tools automatically. A coding agent can use this
loop after implementing a feature or while investigating a bug:

```text
1. Start the platform automation server
2. Capture a screenshot and inspect interactive elements, scoped to the iOS app when needed
3. Reproduce the flow with selector-based taps, swipes, text input, and explicit waits
4. Check the outcome through element lookup, UI hierarchy, and screenshots
5. Correlate UI evidence with application and device logs
6. Fix and repeat
```

Android requires `install_automation_server` once before its first server
start. iOS uses an XCUITest bundle and currently supports simulators. VisionTest
handles device interaction and UI evidence; the agent can combine those results
with logs available in its development environment.

| Area | Capabilities |
| --- | --- |
| Server | install, start, stop, and status |
| Inspection | hierarchy, interactive elements, element lookup, waits, device info, screenshots |
| Interaction | selector-based and coordinate taps, long press, double tap, coordinate and directional swipes, element swipe, targeted text input, home/back |
| Keyboard and alerts | Android key presses and focused-field clearing; iOS keyboard dismissal and app/system alert handling |
| Apps and devices | discover devices, list and inspect apps, launch apps |

## CLI

The same backend is available without an MCP client:

```bash
visiontest start_automation_server -p android
visiontest get_interactive_elements -p android --json
visiontest find_element -p android --text "Login" --json
visiontest tap_on_element -p android --resource-id "com.example:id/login" --timeout 5000
visiontest wait_for_element -p android --text "Welcome" --timeout 5000
visiontest screenshot -p android --output ./after.png
visiontest stop_automation_server -p android
```

Device commands require `--platform android` or `--platform ios` (`-p`).
`init`, root `--help`, and root `--version` do not use a platform.
On iOS, `--bundle-id` scopes inspection and element operations to an app:

```bash
visiontest get_interactive_elements -p ios --bundle-id com.example.app --json
visiontest get_ui_hierarchy -p ios --bundle-id com.example.app
visiontest tap_on_element -p ios --bundle-id com.example.app --text "Continue" --timeout 5000
```

`tap_on_element` prefers stable selectors over coordinates. It requires one of
`--text`, `--text-contains`, `--resource-id`, `--class-name`, or
`--content-description`; `--bundle-id` is iOS scope, not a selector, and is
rejected on Android. The timeout defaults to 10 seconds and cannot exceed 30
seconds. Update the Android automation APK pair or iOS automation bundle when
upgrading to a VisionTest release that introduces this native operation.

Run `visiontest --help` for all 29 commands.

### Forms, gestures, and permissions

Use targeted input to wait for a field, focus it, and type in one native request.
For an Android form submitted with the keyboard:

```bash
visiontest input_text -p android "" --target-resource-id "com.example:id/search"
visiontest clear_text -p android
visiontest input_text -p android "coffee" --target-resource-id "com.example:id/search"
visiontest press_key -p android enter
visiontest wait_for_element -p android --text "Search results" --timeout 5000
```

`clear_text` clears the focused editable Android field. `press_key` accepts
`enter`, `tab`, `backspace`, `delete`, `escape`, or a nonnegative integer Android
key code. Backspace deletes before the cursor; delete removes text after it.

For an iOS permission flow, choose the exact button label shown by the dialog:

```bash
visiontest tap_on_element -p ios --bundle-id com.example.app --text "Enable notifications"
visiontest handle_alert -p ios accept --bundle-id com.example.app --button-label "Allow"
visiontest wait_for_element -p ios --bundle-id com.example.app --text "Notifications enabled"
```

`handle_alert` searches the scoped or active app first, then system alerts in
SpringBoard. Without `--button-label`, `accept` taps the last enabled, hittable
button and `dismiss` taps the first. `dismiss_keyboard -p ios` hides a visible
software keyboard and checks that it disappeared without submitting the field.
Both commands accept optional `--bundle-id`.

`long_press` and `double_tap` accept either selectors or a complete `--x X --y Y`
pair. Prefer selectors when stable element identity matters. Use coordinates
when the screen location is intentional. Long press lasts 800 ms.

Input target flags are `--target-text`, `--target-text-contains`,
`--target-resource-id`, `--target-class-name`, and `--target-content-description`.
Selector gestures and targeted input accept `--timeout` from 1 through 30,000 ms,
defaulting to 10,000 ms. Input lookup, tap, focus, and typing share that budget.
Coordinate gestures and input without selectors reject a timeout. Optional
iOS `--bundle-id` must be nonblank and does not count as a selector; Android
rejects it.

Upgrade both Android automation APKs or the iOS XCUITest bundle before using
these new native methods or targeted input. Updating only the CLI/MCP JAR is
insufficient. Restart the automation server after upgrading its artifacts.
Inspect returned text and verify the app outcome. A normal operation failure
still exits 0. Thrown failures keep the exit codes below.

Six inspection commands support `--json`: `get_interactive_elements`,
`get_device_info`, `find_element`, `list_apps`, `info_app`, and
`available_device`. See the [JSON schemas and examples](docs/cli-json.md).

### Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Command completed; inspect `found` or `success` for operation outcomes |
| 1 | Generic failure |
| 2 | Invalid or missing arguments |
| 3 | Automation server unreachable |
| 4 | Device or simulator unavailable |
| 5 | Platform unsupported by the command |

### Project-local agent instructions

```bash
visiontest init --agent claude,opencode,codex
```

This installs or refreshes VisionTest skill files so supported agents can
discover the CLI workflow inside a project. The shared skill source is
[agent-instructions.md](app/src/main/resources/agent-instructions.md).

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `VISION_TEST_LOG_LEVEL` | `PRODUCTION` | `PRODUCTION`, `DEVELOPMENT`, or `DEBUG` |
| `VISION_TEST_APK_PATH` | auto-detected | Explicit Android test APK path |
| `VISION_TEST_IOS_PROJECT_PATH` | auto-detected | Explicit iOS `.xcodeproj` path |
| `VISIONTEST_DIR` | `~/.local/share/visiontest` | Install directory under `$HOME` |

Android uses port 9008 with automatic ADB forwarding. iOS uses port 9009 on the
simulator host network.

## Reference

- [CLI contract](docs/agentico/specs/cli.md)
- [JSON output](docs/cli-json.md)
- [Element waits](docs/agentico/specs/element-waits.md)
- [Element swipe](docs/agentico/specs/element-swipe.md)
- [Missing interactions](docs/agentico/specs/missing-interactions.md)
- [Screenshots](docs/agentico/specs/screenshots.md)
- [Contributing](CONTRIBUTING.md)
- [Open issues](https://github.com/docer1990/visiontest/issues)

## License

VisionTest is available under the [MIT License](LICENSE).
