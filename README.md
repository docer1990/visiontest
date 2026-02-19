# VisionTest - MCP Server for Mobile Automation

A platform-agnostic interface for mobile automation that enables LLMs and agents to interact with native mobile applications and devices. Supports Android devices (emulators and physical) and iOS simulators.

## Overview

VisionTest is an MCP (Model Context Protocol) server that provides a standardized way for AI agents and Large Language Models to interact with mobile devices. The project consists of three main components:

1. **MCP Server** (`app/`) - Kotlin/JVM server that exposes mobile automation tools via Model Context Protocol
2. **Android Automation Server** (`automation-server/`) - Native Android app with UIAutomator API access via JSON-RPC, using the **instrumentation pattern** (like Maestro/Appium)
3. **iOS Automation Server** (`ios-automation-server/`) - Native iOS app with XCUITest access via JSON-RPC, using the same instrumentation pattern adapted for iOS

This architecture allows for:

- Device detection and information retrieval
- Application management (listing, info retrieval, launching)
- Direct UIAutomator access for advanced Android UI automation
- Direct XCUITest access for iOS simulator UI automation
- Secure automation via instrumentation frameworks on both platforms
- Scalable automation across multiple device types

## Features

### MCP Server
- **Device Management**: Detect and interact with connected Android devices and iOS simulators
- **App Management**: List installed apps, get detailed app information, and launch apps
- **UI Automation**: Get UI hierarchy, find elements, interact with UI via UIAutomator
- **Robust Error Handling**: Comprehensive exception framework with descriptive error messages
- **Performance Optimizations**: Device list caching to reduce ADB command overhead
- **Retry Logic**: Automatic retries with exponential backoff for flaky device operations

### Android Automation Server
- **Instrumentation Pattern**: Uses Android's instrumentation framework for secure UIAutomator access
- **UIAutomator Integration**: Direct access to Android UIAutomator API
- **Flutter App Support**: Reflection-based hierarchy dumping via `getWindowRoots()` for Flutter and other frameworks
- **JSON-RPC Server**: HTTP-based JSON-RPC 2.0 interface for automation commands
- **Configuration UI**: Simple interface showing setup instructions and port configuration
- **No Exported Services**: Only accessible via ADB instrumentation for security

### iOS Automation Server
- **XCUITest Framework**: Uses Apple's XCUITest for iOS simulator UI automation
- **JSON-RPC Server**: HTTP-based JSON-RPC 2.0 interface (via Swifter library)
- **No Port Forwarding**: iOS simulators share the Mac's network stack
- **Full UI Automation**: Tap, swipe, find elements, dump hierarchy, get interactive elements
- **Lightweight**: Swifter HTTP server in a UI test bundle

## Prerequisites

- **JDK 17 or higher**
- **Kotlin 2.1+**
- **Android Platform Tools**: Contains ADB for device communication
   - [Download Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)
   - Add the platform-tools directory to your system PATH
- **Android SDK** (for building the Automation Server app)
- **Connected Android device(s) or emulator(s)** with USB Debugging enabled
   - Minimum SDK: 21 (Android 5.0)
   - Target SDK: 34 (Android 14)
- **Xcode Command Line Tools** (for iOS simulator support on macOS)

## Installation

### 1. Clone and Build

```bash
git clone https://github.com/docer1990/visiontest.git
cd visiontest

# Build entire project
./gradlew build

# Build MCP Server JAR
./gradlew shadowJar
# Output: app/build/libs/visiontest.jar

# Build Automation Server APKs
./gradlew :automation-server:assembleDebug :automation-server:assembleDebugAndroidTest
# Output:
#   - automation-server/build/outputs/apk/debug/automation-server-debug.apk
#   - automation-server/build/outputs/apk/androidTest/debug/automation-server-debug-androidTest.apk
```

### 2. Install Automation Server on Device

Both APKs (main and test) are required for instrumentation-based automation:

```bash
# Connect your Android device via USB (or start an emulator)
adb devices

# Install both APKs
./gradlew :automation-server:installDebug :automation-server:installDebugAndroidTest

# Or manually:
adb install automation-server/build/outputs/apk/debug/automation-server-debug.apk
adb install automation-server/build/outputs/apk/androidTest/debug/automation-server-debug-androidTest.apk
```

### 3. Configure Claude Desktop

Create/edit the MCP configuration file:
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

#### Option A: Using the Launcher Script (Recommended)

```json
{
  "mcpServers": {
    "visiontest": {
      "command": "/ABSOLUTE/PATH/TO/visiontest/run-visiontest.sh"
    }
  }
}
```

#### Option B: Manual Configuration

```json
{
  "mcpServers": {
    "visiontest": {
      "command": "/path/to/java",
      "args": ["-jar", "/ABSOLUTE/PATH/TO/visiontest/app/build/libs/visiontest.jar"],
      "env": {
        "PATH": "/path/to/android/sdk/platform-tools:/usr/bin:/bin",
        "ANDROID_HOME": "/path/to/android/sdk"
      }
    }
  }
}
```

## Usage

### Available MCP Tools

#### Device Management

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

#### UI Automation (Android)

| Tool | Description |
|------|-------------|
| `install_automation_server` | Install both APKs on device |
| `start_automation_server` | Start JSON-RPC server via instrumentation |
| `automation_server_status` | Check if server is running |
| `get_ui_hierarchy` | Get XML of all visible UI elements |
| `get_interactive_elements` | Get filtered list of interactive elements |
| `find_element` | Find element by text, resourceId, className, etc. |
| `android_tap_by_coordinates` | Tap at screen coordinates |
| `android_swipe` | Swipe by coordinates |
| `android_swipe_direction` | Swipe by direction with distance and speed |
| `android_swipe_on_element` | Swipe on a specific element |
| `android_get_device_info` | Get display size, rotation, SDK version |
| `android_press_back` | Press the back button |
| `android_press_home` | Press the home button |

#### UI Automation (iOS)

| Tool | Description |
|------|-------------|
| `ios_start_automation_server` | Build + start XCUITest server on simulator |
| `ios_automation_server_status` | Check if server is running |
| `ios_get_ui_hierarchy` | Get XML of all visible UI elements |
| `ios_get_interactive_elements` | Get filtered list of interactive elements |
| `ios_find_element` | Find element by text, identifier, etc. |
| `ios_tap_by_coordinates` | Tap at screen coordinates |
| `ios_swipe` | Swipe by coordinates |
| `ios_swipe_direction` | Swipe by direction with distance and speed |
| `ios_get_device_info` | Get display size, rotation, iOS version |
| `ios_press_home` | Press home button |
| `ios_stop_automation_server` | Stop the running XCUITest server |

#### Typical Android Workflow

```
1. install_automation_server     →  Install APKs (one-time setup)
2. start_automation_server       →  Start the JSON-RPC server
3. get_interactive_elements      →  Get interactive elements with tap coordinates
4. android_tap_by_coordinates    →  Tap using centerX/centerY
```

#### Typical iOS Workflow

```
1. ios_start_automation_server   →  Build + start XCUITest server
2. ios_get_interactive_elements  →  Get interactive elements with tap coordinates
3. ios_tap_by_coordinates        →  Tap using centerX/centerY
```

### JSON-RPC API

Both automation servers expose a JSON-RPC 2.0 API with compatible method names:

**Android**: `POST http://localhost:9008/jsonrpc` | Health: `GET http://localhost:9008/health`
**iOS**: `POST http://localhost:9009/jsonrpc` | Health: `GET http://localhost:9009/health`

#### Available Methods

| Method | Parameters | Android | iOS |
|--------|------------|---------|-----|
| `ui.dumpHierarchy` | - | Yes | Yes |
| `ui.tapByCoordinates` | `x`, `y` | Yes | Yes |
| `ui.swipe` | `startX`, `startY`, `endX`, `endY`, `steps` | Yes | Yes |
| `ui.swipeByDirection` | `direction`, `distance`, `speed` | Yes | Yes |
| `ui.swipeOnElement` | `direction`, selector, `speed` | Yes | No |
| `ui.findElement` | `text`, `resourceId`, etc. | Yes | Yes |
| `ui.getInteractiveElements` | `includeDisabled` | Yes | Yes |
| `device.getInfo` | - | Yes | Yes |
| `device.pressBack` | - | Yes | No |
| `device.pressHome` | - | Yes | Yes |

#### Example Requests

```bash
# Android
curl -X POST http://localhost:9008/jsonrpc \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"ui.dumpHierarchy","id":1}'

# iOS
curl -X POST http://localhost:9009/jsonrpc \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"device.getInfo","id":1}'
```

## Architecture

### Project Structure

```
visiontest/
├── app/                              # MCP Server (Kotlin/JVM)
│   └── src/main/kotlin/com/example/visiontest/
│       ├── Main.kt                   # Entry point
│       ├── ToolFactory.kt            # MCP tool registration
│       ├── android/
│       │   ├── Android.kt            # ADB communication (Adam library)
│       │   └── AutomationClient.kt   # JSON-RPC client (Android)
│       ├── ios/
│       │   ├── IOSManager.kt         # iOS simulator operations
│       │   └── IOSAutomationClient.kt # JSON-RPC client (iOS)
│       └── config/
│           ├── AppConfig.kt          # MCP server config
│           ├── AutomationConfig.kt   # Android automation constants
│           └── IOSAutomationConfig.kt # iOS automation constants
│
├── automation-server/                # Android Automation Server
│   └── src/
│       ├── main/                     # Main app (config UI only)
│       │   ├── MainActivity.kt
│       │   ├── config/ServerConfig.kt
│       │   ├── jsonrpc/JsonRpcModels.kt
│       │   └── uiautomator/
│       │       ├── BaseUiAutomatorBridge.kt
│       │       └── UiAutomatorModels.kt
│       └── androidTest/              # Instrumentation (actual server)
│           ├── AutomationServerTest.kt
│           ├── AutomationInstrumentationRunner.kt
│           ├── JsonRpcServerInstrumented.kt
│           └── UiAutomatorBridgeInstrumented.kt
│
├── ios-automation-server/            # iOS Automation Server (Xcode)
│   ├── IOSAutomationServer.xcodeproj
│   ├── IOSAutomationServer/          # Minimal host app
│   │   └── AppDelegate.swift
│   └── IOSAutomationServerUITests/   # XCUITest server
│       ├── AutomationServerUITest.swift   # Entry point
│       ├── Server/JsonRpcServer.swift     # Swifter HTTP server
│       ├── Bridge/XCUITestBridge.swift    # All XCUITest logic
│       └── Models/
│           ├── JsonRpcModels.swift
│           └── AutomationModels.swift
│
├── CLAUDE.md                         # AI assistant context
├── LEARNING.md                       # Architecture decisions & learnings
└── build.gradle.kts                  # Root build config
```

### Why Instrumentation?

The automation server uses Android's instrumentation framework instead of a regular service:

| Approach | UIAutomator Access | Security |
|----------|-------------------|----------|
| Exported Service | No | Risk |
| Regular Service | No | Safe |
| **Instrumentation** | **Yes** | **Safe** |

UIAutomator requires a valid `Instrumentation` object to access `UiAutomation`. Only the test framework provides this - creating an empty `Instrumentation()` doesn't work.

### Flutter App Support

The automation server uses a Maestro-inspired approach for Flutter app support:

- **Reflection-based hierarchy**: Uses `UiDevice.getWindowRoots()` via reflection to access all accessibility window roots
- **Cross-app windows**: Enables `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` (API 24+) for accessing windows from other apps
- **Uncompressed hierarchy**: Sets `compressedLayoutHierarchy` to false to expose all accessibility nodes
- **WebView handling**: Includes WebView contents that may report as invisible

**Finding Elements in Flutter Apps:**

Flutter apps expose text labels via `content-desc` (contentDescription) instead of `text`. When searching for elements:

1. First try `find_element` with the `text` parameter
2. If not found, retry with `contentDescription` parameter

```bash
# Native Android app - use text
curl -X POST http://localhost:9008/jsonrpc \
  -d '{"jsonrpc":"2.0","method":"ui.findElement","params":{"text":"Log In"},"id":1}'

# Flutter app - use contentDescription
curl -X POST http://localhost:9008/jsonrpc \
  -d '{"jsonrpc":"2.0","method":"ui.findElement","params":{"contentDescription":"Log In"},"id":1}'
```

### Manual Testing

```bash
# Terminal 1: Start the server
adb shell am instrument -w -e port 9008 \
  -e class com.example.automationserver.AutomationServerTest#runAutomationServer \
  com.example.automationserver.test/com.example.automationserver.AutomationInstrumentationRunner

# Terminal 2: Test the server
adb forward tcp:9008 tcp:9008
curl http://localhost:9008/health

# Stop the server
adb shell am force-stop com.example.automationserver
```

## Testing

```bash
# Run all unit tests
./gradlew test

# Run only MCP server (app/) tests
./gradlew :app:test

# Run a specific test class
./gradlew test --tests "ErrorHandlerTest"
```

### Test Coverage

| Module | Test File | Tests | Coverage Area |
|--------|-----------|-------|---------------|
| `app/` | `ErrorHandlerTest.kt` | 18 | Exception-to-error-code mappings, retry with exponential backoff |
| `app/` | `IOSSimulatorParsingTest.kt` | 20 | Device list parsing, plist parsing, bundle ID & shell command validation |
| `app/` | `AndroidValidationTest.kt` | 18 | Package name validation, ADB argument validation (forward, shell, install) |
| `app/` | `AppConfigTest.kt` | 6 | Default configuration values |
| `app/` | `ToolFactoryHelpersTest.kt` | 8 | Property extraction, pattern matching, app info formatting |

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `VISION_TEST_LOG_LEVEL` | `PRODUCTION` | `PRODUCTION`, `DEVELOPMENT`, `DEBUG` |
| `VISION_TEST_APK_PATH` | (auto-detected) | Explicit path to test APK |

### Default Timeouts (hardcoded in `AppConfig.kt`)

- ADB timeout: 5000ms
- Device cache validity: 1000ms
- Tool execution timeout: 10000ms

### Automation Server

- **Android Default Port**: 9008 (configurable via app UI, range: 1024-65535)
- **iOS Default Port**: 9009 (no port forwarding needed)
- **Port Forwarding**: Automatically set up by MCP tools (Android only)

## Error Codes

| Code | Description |
|------|-------------|
| `ERR_NO_DEVICE` | No Android device available |
| `ERR_CMD_FAILED` | Command execution failed |
| `ERR_PKG_NOT_FOUND` | Package not found |
| `ERR_TIMEOUT` | Operation timed out |
| `ERR_NO_SIMULATOR` | No iOS simulator available |

## Extending

### Adding New JSON-RPC Methods

1. Add method to `BaseUiAutomatorBridge.kt` (uses `getUiDevice()`, `getUiAutomation()`, `getDisplayRect()`)
2. Register in `JsonRpcServerInstrumented.kt` `executeMethod()`
3. Add client method to `AutomationClient.kt`
4. Create MCP tool in `ToolFactory.kt`

### Adding New MCP Tools

1. Create method in `ToolFactory.kt`
2. Register in `registerAllTools()`

## Future Plans

- [ ] Text input/typing support
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

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the LICENSE file for details.
