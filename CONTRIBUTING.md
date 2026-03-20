# Contributing to VisionTest

## Build from Source

```bash
git clone https://github.com/docer1990/visiontest.git
cd visiontest

# Build MCP Server JAR
./gradlew shadowJar
# Output: app/build/libs/visiontest.jar

# Build Android Automation Server APKs
./gradlew :automation-server:assembleDebug :automation-server:assembleDebugAndroidTest

# Install both APKs on a connected device (required for Android automation)
./gradlew :automation-server:installDebug :automation-server:installDebugAndroidTest

# Build iOS test bundle (macOS only)
xcodebuild build-for-testing \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17'
```

### Configure Claude Desktop (from source)

```json
{
  "mcpServers": {
    "visiontest": {
      "command": "/ABSOLUTE/PATH/TO/visiontest/run-visiontest.sh"
    }
  }
}
```

The `run-visiontest.sh` launcher handles `JAVA_HOME`, `ANDROID_HOME`, and APK path setup automatically.

## Architecture

VisionTest has three components:

1. **MCP Server** (`app/`) — Kotlin/JVM server that exposes mobile automation tools via Model Context Protocol (stdio transport)
2. **Android Automation Server** (`automation-server/`) — Native Android app with UIAutomator API access via JSON-RPC, using the instrumentation pattern (like Maestro/Appium)
3. **iOS Automation Server** (`ios-automation-server/`) — Native iOS app with XCUITest access via JSON-RPC

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

The Android automation server uses the instrumentation framework instead of a regular service:

| Approach | UIAutomator Access | Security |
|----------|-------------------|----------|
| Exported Service | No | Risk |
| Regular Service | No | Safe |
| **Instrumentation** | **Yes** | **Safe** |

UIAutomator requires a valid `Instrumentation` object to access `UiAutomation`. Only the test framework provides this — creating an empty `Instrumentation()` doesn't work.

See [LEARNING.md](LEARNING.md) for deeper architecture decision records.

### Flutter App Support

Flutter apps expose text labels via `content-desc` (contentDescription) instead of `text`. When searching for elements:

1. First try `find_element` with the `text` parameter
2. If not found, retry with `contentDescription` parameter

The automation server uses reflection-based hierarchy dumping via `UiDevice.getWindowRoots()` (inspired by Maestro) to support Flutter and other cross-platform frameworks.

## JSON-RPC API

Both automation servers expose a JSON-RPC 2.0 API. Most users interact through the MCP tools, but the API is useful for debugging and direct integration.

**Android**: `POST http://localhost:9008/jsonrpc` | Health: `GET http://localhost:9008/health`
**iOS**: `POST http://localhost:9009/jsonrpc` | Health: `GET http://localhost:9009/health`

### Available Methods

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
| `ui.inputText` | `text` | Yes | Yes |
| `device.pressBack` | - | Yes | No |
| `device.pressHome` | - | Yes | Yes |

### Example Requests

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

## MCP Tools Reference

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
| `android_input_text` | Type text into the currently focused element |
| `android_press_back` | Press the back button |
| `android_press_home` | Press the home button |

### UI Automation (iOS)

| Tool | Description |
|------|-------------|
| `ios_start_automation_server` | Start XCUITest server (pre-built bundle or source build) |
| `ios_automation_server_status` | Check if server is running |
| `ios_get_ui_hierarchy` | Get XML of all visible UI elements |
| `ios_get_interactive_elements` | Get filtered list of interactive elements |
| `ios_find_element` | Find element by text, identifier, etc. |
| `ios_tap_by_coordinates` | Tap at screen coordinates |
| `ios_swipe` | Swipe by coordinates |
| `ios_swipe_direction` | Swipe by direction with distance and speed |
| `ios_get_device_info` | Get display size, rotation, iOS version |
| `ios_input_text` | Type text into the currently focused element |
| `ios_press_home` | Press home button |
| `ios_stop_automation_server` | Stop the running XCUITest server |

## Testing

### Running Tests

```bash
# Run all Gradle unit tests (MCP server + Android automation server)
./gradlew test

# Run only MCP server (app/) tests
./gradlew :app:test

# Run only Android automation server tests
./gradlew :automation-server:test

# Run a specific test class
./gradlew test --tests "ErrorHandlerTest"

# Run iOS automation server unit tests
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerTests
```

All Gradle tests are pure JVM unit tests (no device or emulator required). iOS tests run on the simulator but don't need a running automation server.

### Test Coverage

| Module | Test File | Coverage Area |
|--------|-----------|---------------|
| `app/` | `ErrorHandlerTest.kt` | Exception-to-error-code mappings, retry with exponential backoff |
| `app/` | `ErrorHandlerCoroutineTest.kt` | Exponential backoff delays with `TestCoroutineScheduler` |
| `app/` | `IOSSimulatorParsingTest.kt` | Device list parsing, plist parsing, bundle ID & shell command validation |
| `app/` | `IOSSimulatorTest.kt` | Simulator operations with mocked ProcessExecutor |
| `app/` | `ProcessExecutorTest.kt` | Exit codes, stdout capture, timeout handling |
| `app/` | `IOSAutomationClientTest.kt` | JSON-RPC requests, `isServerRunning`, Gson serialization |
| `app/` | `AndroidValidationTest.kt` | Package name validation, ADB argument validation |
| `app/` | `AutomationClientTest.kt` | `sendRequest` POST/params/errors, `isServerRunning` health check |
| `app/` | `AppConfigTest.kt` | Default configuration values |
| `app/` | `ToolFactoryHelpersTest.kt` | Property extraction, pattern matching, app info formatting |
| `app/` | `ToolFactoryPathTest.kt` | `findProjectRoot`, `findAutomationServerApk`, `findXctestrun` |
| `automation-server/` | `JsonRpcModelsTest.kt` | JSON-RPC error factory methods, request/response defaults |
| `automation-server/` | `UiAutomatorModelsTest.kt` | Data classes, default values, enum entries |
| `automation-server/` | `ServerConfigPortTest.kt` | Port validation boundaries |
| `automation-server/` | `XmlUtilsTest.kt` | XML character stripping |
| `ios-automation-server/` | `JsonRpcModelsTests.swift` | JSON-RPC request parsing, error factory methods, error codes |
| `ios-automation-server/` | `AutomationModelsTests.swift` | Result model `toDictionary()` conversions, enum raw values |
| `ios-automation-server/` | `HelpersTests.swift` | `escapeXML`, `boundsString`, `intParam` type coercion |

## Manual Testing

### Android

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

### iOS

```bash
# Terminal 1: Start the server
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testRunAutomationServer

# Terminal 2: Test the server (no port forwarding needed)
curl http://localhost:9009/health
curl -X POST http://localhost:9009/jsonrpc -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"device.getInfo","id":1}'

# Stop the server: kill the xcodebuild process (Ctrl+C in Terminal 1)
```

## Extending VisionTest

### Adding New JSON-RPC Methods

1. Add method to `BaseUiAutomatorBridge.kt` (uses `getUiDevice()`, `getUiAutomation()`, `getDisplayRect()`)
2. Register in `JsonRpcServerInstrumented.kt` `executeMethod()`
3. Add client method to `AutomationClient.kt`
4. Create MCP tool in `ToolFactory.kt`

### Adding New MCP Tools

1. Create method in `ToolFactory.kt`
2. Register in `registerAllTools()`

## Error Codes

| Code | Description |
|------|-------------|
| `ERR_NO_DEVICE` | No Android device available |
| `ERR_CMD_FAILED` | Command execution failed |
| `ERR_PKG_NOT_FOUND` | Package not found |
| `ERR_TIMEOUT` | Operation timed out |
| `ERR_NO_SIMULATOR` | No iOS simulator available |

## Additional Resources

- [CLAUDE.md](CLAUDE.md) — AI assistant context with full build commands and patterns
- [LEARNING.md](LEARNING.md) — Architecture decision records and design rationale
