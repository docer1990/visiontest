# VisionTest - MCP Server for Mobile Automation

An MCP server that lets AI agents interact with Android devices and iOS simulators — tap, swipe, type, read UI elements, and launch apps.

## What It Does

- **Android + iOS** automation through a single MCP server
- **UI interaction**: tap, swipe, type text, find elements, read screen hierarchy
- **App management**: list, inspect, and launch apps
- **Device detection**: automatically finds connected Android devices and booted iOS simulators
- **Zero-config iOS**: uses pre-built test bundle when installed, falls back to source build if needed

## Prerequisites

- **JDK 17 or higher**
- **macOS or Linux** (arm64 or x86_64)
- **Android Platform Tools** (for Android automation): [Download](https://developer.android.com/tools/releases/platform-tools)
- **Xcode Command Line Tools** (for iOS simulator automation, macOS only)

## Installation

### Quick Install (Recommended)

```bash
curl -fsSL https://github.com/docer1990/visiontest/releases/latest/download/install.sh | bash
```

This will:
- Check that Java 17+ is installed
- Download the latest release JAR, Android APKs, and iOS test bundle
- Create a `visiontest` command in `~/.local/bin/`
- Verify all downloads via SHA-256 checksums

You can customize the install directory:

```bash
VISIONTEST_DIR="$HOME/my-tools/visiontest" curl -fsSL https://github.com/docer1990/visiontest/releases/latest/download/install.sh | bash
```

To update, re-run the same command.

### Configure Your AI Coding Tool

<details>
<summary><b>Claude Code</b></summary>

```bash
claude mcp add visiontest java -- -jar ~/.local/share/visiontest/visiontest.jar
```
</details>

<details>
<summary><b>Claude Desktop</b></summary>

Edit the config file:
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

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

> **Note:** Replace `/ABSOLUTE/PATH/TO` with your home directory (e.g. `/Users/yourname` on macOS, `/home/yourname` on Linux). JSON does not expand `~`.
</details>

<details>
<summary><b>GitHub Copilot CLI</b></summary>

Add to `~/.copilot/mcp-config.json`:

```json
{
  "mcpServers": {
    "visiontest": {
      "command": "java",
      "args": ["-jar", "/ABSOLUTE/PATH/TO/.local/share/visiontest/visiontest.jar"],
      "type": "stdio"
    }
  }
}
```
</details>

<details>
<summary><b>OpenAI Codex CLI</b></summary>

```bash
codex mcp add visiontest -- java -jar ~/.local/share/visiontest/visiontest.jar
```

Or add to `~/.codex/config.toml`:

```toml
[mcp_servers.visiontest]
command = "java"
args = ["-jar", "/ABSOLUTE/PATH/TO/.local/share/visiontest/visiontest.jar"]
```
</details>

<details>
<summary><b>OpenCode</b></summary>

Add to `opencode.json` (project root or `~/.config/opencode/opencode.json`):

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

### Build from Source

For development or contributing, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Usage

Your AI coding tool discovers all available tools automatically via MCP. Just ask it to interact with a device and it will use the right tools.

### Android Workflow

```
1. install_automation_server     →  Install APKs (one-time setup)
2. start_automation_server       →  Start the JSON-RPC server
3. get_interactive_elements      →  Get interactive elements with tap coordinates
4. android_tap_by_coordinates    →  Tap using centerX/centerY
5. android_input_text            →  Type text into focused field
```

### iOS Workflow

```
1. ios_start_automation_server   →  Start XCUITest server (pre-built or source build)
2. ios_get_interactive_elements  →  Get interactive elements with tap coordinates
3. ios_tap_by_coordinates        →  Tap using centerX/centerY
4. ios_input_text                →  Type text into focused field
```

### Available Tools

**Device Management:** `available_device_android`, `list_apps_android`, `info_app_android`, `launch_app_android`, `ios_available_device`, `ios_list_apps`, `ios_info_app`, `ios_launch_app`

**Android Automation:** `install_automation_server`, `start_automation_server`, `automation_server_status`, `get_ui_hierarchy`, `get_interactive_elements`, `find_element`, `android_tap_by_coordinates`, `android_swipe`, `android_swipe_direction`, `android_swipe_on_element`, `android_get_device_info`, `android_input_text`, `android_press_back`, `android_press_home`

**iOS Automation:** `ios_start_automation_server`, `ios_automation_server_status`, `ios_get_ui_hierarchy`, `ios_get_interactive_elements`, `ios_find_element`, `ios_tap_by_coordinates`, `ios_swipe`, `ios_swipe_direction`, `ios_get_device_info`, `ios_input_text`, `ios_press_home`, `ios_stop_automation_server`

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `VISION_TEST_LOG_LEVEL` | `PRODUCTION` | `PRODUCTION`, `DEVELOPMENT`, `DEBUG` |
| `VISION_TEST_APK_PATH` | (auto-detected) | Explicit path to Android test APK |
| `VISION_TEST_IOS_PROJECT_PATH` | (auto-detected) | Explicit path to iOS `.xcodeproj` |
| `VISIONTEST_DIR` | `~/.local/share/visiontest` | Override install directory (must be under `$HOME`) |

### Ports

- **Android**: 9008 (requires ADB port forwarding, set up automatically)
- **iOS**: 9009 (no port forwarding needed — simulators share the Mac's network)

## Future Plans

- [x] Text input/typing support
- [ ] Screenshot capture via UIAutomator / XCUITest
- [ ] Long press operations
- [ ] Wait/sync operations for E2E testing
- [ ] Multi-device coordination
- [ ] Generic app install/uninstall
- [ ] Clipboard operations (read/write)
- [ ] Physical iOS device support
- [ ] WebSocket support for real-time updates
- [ ] Notification/status bar interaction
- [ ] Permission dialog automation
- [ ] Video recording of automation sessions

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build-from-source instructions, architecture details, JSON-RPC API reference, testing guide, and how to extend VisionTest.

## License

This project is licensed under the MIT License - see the LICENSE file for details.
