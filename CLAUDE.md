# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Important**: Before modifying any Kotlin MCP server code in `app/src/`, you MUST read [`kotlin-mcp-server.instruction.md`](kotlin-mcp-server.instruction.md). It contains required patterns for server setup, tool registration, transport configuration, coroutine usage, dependency injection, and error handling.

## Build and Run Commands

```bash
# Build entire project
./gradlew build

# === MCP Server (app module) ===
./gradlew run                           # Run the MCP server
./gradlew test                          # Run all tests (app + automation-server)
./gradlew :app:test                     # Run only MCP server unit tests
./gradlew :automation-server:test       # Run only automation server unit tests
./gradlew test --tests "ErrorHandlerTest"  # Run a specific test class
./gradlew shadowJar                     # Build fat JAR -> app/build/libs/visiontest.jar

# === Installation & Release ===
bash install.sh                         # Install locally (or curl -fsSL <url> | bash)
# Release: push a tag to trigger the GitHub Actions release workflow
# git tag v0.1.0 && git push --tags    # Builds JAR, runs tests, creates GitHub Release

# === Automation Server Android App ===
./gradlew :automation-server:assembleDebug                # Build debug APK
./gradlew :automation-server:assembleDebugAndroidTest     # Build test APK (instrumentation)
./gradlew :automation-server:installDebug                 # Install main APK
./gradlew :automation-server:installDebugAndroidTest      # Install test APK

# Build and install both APKs (required for instrumentation)
./gradlew :automation-server:assembleDebug :automation-server:assembleDebugAndroidTest
./gradlew :automation-server:installDebug :automation-server:installDebugAndroidTest

# Lint check
./gradlew :automation-server:lint

# Output paths:
# - Main APK: automation-server/build/outputs/apk/debug/automation-server-debug.apk
# - Test APK: automation-server/build/outputs/apk/androidTest/debug/automation-server-debug-androidTest.apk

# === iOS Automation Server ===
# Build for testing (resolves SPM dependencies + compiles)
xcodebuild build-for-testing \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 16'

# Start the automation server (runs XCUITest with JSON-RPC server)
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testRunAutomationServer

# Run iOS unit tests
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:IOSAutomationServerTests

# Test the server (in another terminal)
curl http://localhost:9009/health
```

## Architecture Overview

VisionTest is an MCP (Model Context Protocol) server enabling AI agents to interact with mobile devices. It consists of three modules:

- **MCP Server (`app/`)** — Kotlin/JVM server using stdio transport. Entry point: `Main.kt`. Tool registration via `ToolFactory.kt` + modular `ToolRegistrar` implementations. DSL for timeout/error handling in `tools/ToolDsl.kt`.
- **Automation Server (`automation-server/`)** — Native Android app providing UIAutomator access via JSON-RPC. Uses instrumentation pattern (like Maestro/Appium). The server runs as an instrumentation test (`src/androidTest/`), not as a regular app service.
- **iOS Automation Server (`ios-automation-server/`)** — Native iOS XCUITest bundle providing automation via JSON-RPC. Server runs as a UI test. No port forwarding needed (iOS simulators share Mac's network stack).

Both automation servers expose `GET /health` and `POST /jsonrpc` (JSON-RPC 2.0) endpoints. See `LEARNING.md` for detailed design decisions (instrumentation pattern, Template Method, reflection-based hierarchy dumping, security practices).

## MCP Tools

### Device Management
| Tool | Platform | Description |
|------|----------|-------------|
| `available_device_android` | Android | Get first available device info |
| `list_apps_android` | Android | List installed apps |
| `info_app_android` | Android | Get app details (requires `packageName`) |
| `launch_app_android` | Android | Launch app (requires `packageName`) |
| `ios_available_device` | iOS | Get first available simulator info |
| `ios_list_apps` | iOS | List installed apps |
| `ios_info_app` | iOS | Get app details (requires `bundleId`) |
| `ios_launch_app` | iOS | Launch app (requires `bundleId`) |

### UI Automation (Android)
| Tool | Description |
|------|-------------|
| `install_automation_server` | Install both main and test APKs on device |
| `start_automation_server` | Start server via instrumentation, set up port forwarding |
| `automation_server_status` | Check if automation server is running |
| `get_ui_hierarchy` | Get XML of all UI elements on current screen |
| `find_element` | Find element by text, resourceId, className, etc. |
| `android_tap_by_coordinates` | Tap at screen coordinates (x, y) |
| `android_swipe` | Swipe by coordinates (startX, startY) to (endX, endY) |
| `android_swipe_direction` | Swipe by direction (up/down/left/right) with distance and speed |
| `android_swipe_on_element` | Swipe on a specific element (for carousels, sliders) |
| `android_get_device_info` | Get display size, rotation, and SDK version |
| `get_interactive_elements` | Get filtered list of interactive elements with center coordinates |
| `android_input_text` | Type text into the currently focused element |
| `android_press_back` | Press the back button |
| `android_press_home` | Press the home button |
| `android_screenshot` | Capture the device display and save as a PNG file (optional `outputPath`; defaults to `./screenshots/` in the project's CWD) |

### UI Automation (iOS)
| Tool | Description |
|------|-------------|
| `ios_start_automation_server` | Build + start XCUITest server on simulator |
| `ios_automation_server_status` | Check if iOS automation server is running |
| `ios_get_ui_hierarchy` | Get XML of all UI elements on current screen |
| `ios_find_element` | Find element by text, identifier, elementType, etc. |
| `ios_tap_by_coordinates` | Tap at screen coordinates (x, y) |
| `ios_swipe` | Swipe by coordinates |
| `ios_swipe_direction` | Swipe by direction (up/down/left/right) with distance and speed |
| `ios_get_interactive_elements` | Get filtered list of interactive elements with center coordinates |
| `ios_get_device_info` | Get display size, rotation, and iOS version |
| `ios_input_text` | Type text into the currently focused element |
| `ios_press_home` | Press home button |
| `ios_screenshot` | Capture the simulator display and save as a PNG file (optional `outputPath`; defaults to `./screenshots/` in the project's CWD) |
| `ios_stop_automation_server` | Stop the running XCUITest server |

### Typical Automation Workflow

1. **Install/Start** — `install_automation_server` + `start_automation_server` (Android) or `ios_start_automation_server` (iOS, uses pre-built bundle if available)
2. **Inspect** — `get_interactive_elements` / `ios_get_interactive_elements` (preferred) or `get_ui_hierarchy` / `ios_get_ui_hierarchy` (full XML)
3. **Interact** — `android_tap_by_coordinates` / `ios_tap_by_coordinates` using centerX/centerY from interactive elements
4. **Input** — `android_input_text` / `ios_input_text` for text entry
5. **Navigate** — `android_swipe_direction` / `ios_swipe_direction` (simpler) or coordinate-based swipe (precise)

> **Flutter apps:** Text labels use `content-desc` (contentDescription) instead of `text`. If `find_element` by `text` fails, retry with `contentDescription`.

## CLI Usage

The same operations available as MCP tools can be invoked directly from the command line. Every command requires `--platform android` or `--platform ios` (alias `-p`). With no arguments, `visiontest` starts the MCP stdio server as before.

| Command | Platforms | Required args | Optional flags |
|---------|-----------|---------------|----------------|
| `install_automation_server` | android | — | — |
| `start_automation_server` | android, ios | — | — |
| `automation_server_status` | android, ios | — | — |
| `get_interactive_elements` | android, ios | — | `--include-disabled` |
| `get_ui_hierarchy` | android, ios | — | — |
| `get_device_info` | android, ios | — | — |
| `screenshot` | android, ios | — | `--output PATH` |
| `tap_by_coordinates` | android, ios | `x` `y` (ints) | — |
| `input_text` | android, ios | `text` (string) | — |
| `swipe_direction` | android, ios | `direction` (up\|down\|left\|right) | `--distance`, `--speed` |
| `press_back` | android | — | — |
| `press_home` | android, ios | — | — |
| `launch_app` | android, ios | `id` (string) | — |

### CLI Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Generic failure |
| 2 | Usage error (missing/invalid args) |
| 3 | Automation server not reachable |
| 4 | Device/simulator not found |
| 5 | Platform not supported for this command |

## Key Patterns

- All device operations use suspend functions with coroutine-based async
- Retry logic with exponential backoff in `ErrorHandler.retryOperation()`
- Custom exception hierarchy in `Exceptions.kt` with platform-specific error codes
- Tool timeout wrapper: `ToolScope` DSL with `withTimeout` (default: 10s, 30s for UI hierarchy, 200s for iOS server startup)
- Centralized constants in `AutomationConfig.kt` / `IOSAutomationConfig.kt` — no magic numbers

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `VISION_TEST_LOG_LEVEL` | `PRODUCTION` | `PRODUCTION`, `DEVELOPMENT`, `DEBUG` |
| `VISION_TEST_APK_PATH` | (auto-detected) | Explicit path to test APK |
| `VISION_TEST_IOS_PROJECT_PATH` | (auto-detected) | Explicit path to iOS `.xcodeproj` |
| `VISIONTEST_DIR` | `~/.local/share/visiontest` | Override install directory (must be under `$HOME`) |

### Key Defaults

- Android automation server port: **9008** (range: 1024-65535), requires ADB port forwarding
- iOS automation server port: **9009** (range: 1024-65535), no port forwarding needed
- All Gradle tests are pure JVM (no device/emulator needed). See `.claude/unit-testing-strategy.md` for the testing roadmap.

## Further Reading

- [`LEARNING.md`](LEARNING.md) — Design decisions (instrumentation, Template Method, Flutter support, security)
- [`docs/installation.md`](docs/installation.md) — Installer, release workflow, launcher script, prerequisites
- [`kotlin-mcp-server.instruction.md`](kotlin-mcp-server.instruction.md) — Required Kotlin/MCP patterns
