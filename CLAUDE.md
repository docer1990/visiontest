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

## Installation & Distribution

### One-Command Installer (`install.sh`)

Users install with `curl -fsSL https://github.com/docer1990/visiontest/releases/latest/download/install.sh | bash`. The script:
1. Detects OS (macOS/Linux) and arch (arm64/x86_64)
2. Validates Java 17+ with platform-specific install suggestions
3. Fetches latest release tag from GitHub API, validates format (`v[0-9]*`) and rejects dangerous characters
4. Downloads `visiontest.jar` + SHA-256 checksum, verifies integrity
5. Installs JAR to `~/.local/share/visiontest/` (customizable via `VISIONTEST_DIR` env var, must be under `$HOME`)
6. Creates wrapper script at `~/.local/bin/visiontest`, ensures PATH
7. Auto-configures Claude Desktop (prompts user, backs up existing config, merges via Python)

**Security hardening:** `umask 077`, explicit `chmod` on all files/dirs, tag validation, checksum verification, install path restricted to `$HOME`.

### Release Workflow (`.github/workflows/release.yaml`)

Triggered by `v*` tags. Two jobs:
1. **`test`** — runs `./gradlew test` (all modules) — release is blocked if tests fail
2. **`release`** (needs: test) — builds fat JAR via `shadowJar`, generates SHA-256 checksum, creates GitHub Release with assets: `visiontest.jar`, `visiontest.jar.sha256`, `install.sh`, `run-visiontest.sh`

All GitHub Actions are pinned to commit SHAs for supply-chain security.

### Launcher Script (`run-visiontest.sh`)

Used for development and Claude Desktop config. JAR resolution order:
1. Repo build: `app/build/libs/visiontest.jar` (sets up `ANDROID_HOME`, APK path, `cd` to project root)
2. Installed JAR: `~/.local/share/visiontest/visiontest.jar` (skips Android SDK setup)
3. Error with build/install instructions

## Architecture Overview

VisionTest is an MCP (Model Context Protocol) server enabling AI agents to interact with mobile devices. It consists of three modules:

### MCP Server (`app/`)

Kotlin/JVM server using stdio transport. Key files:
- `Main.kt` - Entry point, initializes managers and connects via stdio
- `ToolFactory.kt` - Registers all MCP tools (Android, iOS, and Automation)
- `android/Android.kt` - ADB communication via Adam library
- `android/AutomationClient.kt` - HTTP client for Android Automation Server JSON-RPC
- `ios/IOSManager.kt` - iOS simulator operations via `xcrun simctl`
- `ios/IOSAutomationClient.kt` - HTTP client for iOS Automation Server JSON-RPC
- `config/AutomationConfig.kt` - Centralized constants for Android automation
- `config/IOSAutomationConfig.kt` - Centralized constants for iOS automation
- `common/DeviceConfig.kt` - Shared interface for both platforms

### Automation Server (`automation-server/`)

Native Android app providing UIAutomator access via JSON-RPC. Uses **instrumentation pattern** (like Maestro/Appium) for secure, privileged access.

**Main App (`src/main/`) - Configuration UI only:**
- `MainActivity.kt` - Shows setup instructions and port configuration
- `config/ServerConfig.kt` - SharedPreferences for port setting
- `jsonrpc/JsonRpcModels.kt` - Request/Response/Error data classes
- `uiautomator/BaseUiAutomatorBridge.kt` - Abstract base class with all UIAutomator logic (includes reflection-based hierarchy dumping)
- `uiautomator/UiAutomatorModels.kt` - Data classes for results

**Instrumentation (`src/androidTest/`) - The actual working server:**
- `AutomationInstrumentationRunner.kt` - Custom test runner capturing arguments
- `AutomationServerTest.kt` - Long-running test hosting the JSON-RPC server
- `JsonRpcServerInstrumented.kt` - Ktor HTTP server with JSON-RPC 2.0
- `UiAutomatorBridgeInstrumented.kt` - Extends BaseUiAutomatorBridge with valid Instrumentation

**JSON-RPC Endpoints:**
- `GET /health` - Server health check
- `POST /jsonrpc` - JSON-RPC 2.0 endpoint

**Supported JSON-RPC Methods:**
| Method | Parameters | Description |
|--------|------------|-------------|
| `ui.dumpHierarchy` | - | Get UI hierarchy XML |
| `ui.tapByCoordinates` | `x`, `y` | Tap at coordinates |
| `ui.swipe` | `startX`, `startY`, `endX`, `endY`, `steps` | Swipe by coordinates |
| `ui.swipeByDirection` | `direction`, `distance`, `speed` | Swipe by direction (up/down/left/right) |
| `ui.swipeOnElement` | `direction`, selector, `speed` | Swipe on a specific element |
| `ui.findElement` | `text`, `resourceId`, etc. | Find UI element |
| `ui.getInteractiveElements` | `includeDisabled` (optional) | Get filtered list of interactive elements |
| `device.getInfo` | - | Get display size, rotation, SDK |
| `ui.inputText` | `text` | Type text into focused element |
| `device.pressBack` | - | Press back button |
| `device.pressHome` | - | Press home button |

### iOS Automation Server (`ios-automation-server/`)

Native iOS app providing XCUITest access via JSON-RPC. Uses **XCUITest framework** (similar pattern to Android's instrumentation).

**Host App (`IOSAutomationServer/`):**
- `AppDelegate.swift` - Minimal host app required by XCUITest

**UI Test Bundle (`IOSAutomationServerUITests/`) - The actual working server:**
- `AutomationServerUITest.swift` - Entry point test that starts the JSON-RPC server
- `Server/JsonRpcServer.swift` - Swifter HTTP server with JSON-RPC 2.0 dispatch
- `Bridge/XCUITestBridge.swift` - All XCUITest automation logic
- `Models/JsonRpcModels.swift` - Request/Response/Error types
- `Models/AutomationModels.swift` - Result types (mirrors UiAutomatorModels.kt)

**Key difference from Android:** iOS simulators share the Mac's network stack, so **no port forwarding is needed**. The server is directly accessible at `localhost:9009`.

**Supported iOS JSON-RPC Methods:**
| Method | Parameters | Description |
|--------|------------|-------------|
| `ui.dumpHierarchy` | - | Get UI hierarchy XML |
| `ui.tapByCoordinates` | `x`, `y` | Tap at coordinates |
| `ui.swipe` | `startX`, `startY`, `endX`, `endY`, `steps` | Swipe by coordinates |
| `ui.swipeByDirection` | `direction`, `distance`, `speed` | Swipe by direction |
| `ui.findElement` | `text`, `resourceId`, etc. | Find UI element |
| `ui.getInteractiveElements` | `includeDisabled` (optional) | Get interactive elements |
| `ui.inputText` | `text` | Type text into focused element |
| `device.getInfo` | - | Get display size, rotation, iOS version |
| `device.pressHome` | - | Press home button |

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

**Typical Android Automation Workflow:**
1. `install_automation_server` - Install both APKs (one-time)
2. `start_automation_server` - Start JSON-RPC server via `am instrument`
3. `get_interactive_elements` - Get filtered list of interactive elements (preferred)
   - OR `get_ui_hierarchy` - Get full XML hierarchy (when you need all elements)
4. `android_tap_by_coordinates` - Tap using centerX/centerY from interactive elements
5. `android_input_text` - Type text into the focused field
6. `android_swipe_direction` - Scroll/swipe by direction (simpler, no coordinates needed)
   - OR `android_swipe` - Swipe by exact coordinates (for precise control)

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
| `ios_stop_automation_server` | Stop the running XCUITest server |

**Typical iOS Automation Workflow:**
1. `ios_start_automation_server` - Build and start XCUITest server (handles build + install)
2. `ios_get_interactive_elements` - Get filtered list of interactive elements (preferred)
   - OR `ios_get_ui_hierarchy` - Get full XML hierarchy (when you need all elements)
3. `ios_tap_by_coordinates` - Tap using centerX/centerY from interactive elements
4. `ios_input_text` - Type text into the focused field
5. `ios_swipe_direction` - Scroll/swipe by direction (simpler, no coordinates needed)

## Instrumentation Pattern

The automation server uses Android's instrumentation framework (like Maestro, Appium) for UIAutomator access:

**Why Instrumentation?**
- UIAutomator requires valid `Instrumentation` with `UiAutomation` connection
- Regular services can't get this - creating empty `Instrumentation()` doesn't work
- Only the test framework provides proper instrumentation context
- Service remains unexported for security

**How it works:**
```bash
# MCP tool executes this command:
adb shell am instrument -w -e port 9008 \
  -e class com.example.automationserver.AutomationServerTest#runAutomationServer \
  com.example.automationserver.test/com.example.automationserver.AutomationInstrumentationRunner
```

**Manual testing:**
```bash
# Start server
adb shell am instrument -w -e port 9008 \
  -e class com.example.automationserver.AutomationServerTest#runAutomationServer \
  com.example.automationserver.test/com.example.automationserver.AutomationInstrumentationRunner

# In another terminal, set up port forwarding and test
adb forward tcp:9008 tcp:9008
curl http://localhost:9008/health

# Stop server
adb shell am force-stop com.example.automationserver
```

## iOS Automation Pattern

The iOS automation server uses Apple's XCUITest framework, mirroring the Android instrumentation pattern:

**Why XCUITest?**
- XCUITest provides full access to the UI element tree via XCUIElement
- It runs as a UI test bundle, with proper accessibility access
- No separate installation step — `xcodebuild test` handles build + install + run

**How it works:**
```bash
# MCP tool executes this command:
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testRunAutomationServer
```

**Manual testing:**
```bash
# Start server (in one terminal)
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testRunAutomationServer

# In another terminal, test directly
curl http://localhost:9009/health
curl -X POST http://localhost:9009/jsonrpc -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"device.getInfo","id":1}'

# Stop server: kill the xcodebuild process (Ctrl+C)
```

**Key differences from Android:**
| Aspect | Android | iOS |
|--------|---------|-----|
| Port forwarding | Required (ADB) | Not needed (shared network) |
| Back button | `device.pressBack()` | No equivalent — tap nav bar back button |
| Starting server | `am instrument -w` | `xcodebuild test -only-testing:` |
| Stopping server | `am force-stop` | Kill xcodebuild process |
| Swipe control | Step count | Duration (steps * 0.05 seconds) |
| Build system | Gradle module | Xcode project |
| Dependencies | Ktor/Netty (Gradle) | Swifter (Swift Package Manager) |
| Default port | 9008 | 9009 |

## Flutter App Support

The automation server uses a reflection-based approach for UI hierarchy dumping, similar to Maestro:

**Key features:**
- Uses `UiDevice.getWindowRoots()` via reflection to access all accessibility window roots
- Enables `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` (API 24+) for cross-app window access
- Sets `compressedLayoutHierarchy` to false to expose all accessibility nodes
- Handles WebView contents that may report as invisible

**Finding elements in Flutter apps:**
> **Important:** Flutter apps expose text labels via `content-desc` (contentDescription) attribute instead of `text`. When using `find_element` on a Flutter app:
> 1. First try finding by `text` parameter
> 2. If not found, retry using `contentDescription` parameter with the same value
>
> Example: To find a "Log In" button in a Flutter app, use `contentDescription: "Log In"` instead of `text: "Log In"`.

**Abstract methods in `BaseUiAutomatorBridge`:**
- `getUiDevice()` - Returns the UiDevice instance
- `getUiAutomation()` - Returns UiAutomation with appropriate flags
- `getDisplayRect()` - Returns display bounds for visibility calculations

## Unit Tests

All Gradle tests are pure JVM (no device/emulator needed). iOS tests run on the simulator but don't need a running automation server.

### MCP Server (`app/src/test/kotlin/com/example/visiontest/`)

| Test File | What It Tests |
|-----------|---------------|
| `utils/ErrorHandlerTest.kt` | 12 exception→error-code mappings, `retryOperation` exponential backoff |
| `utils/ErrorHandlerCoroutineTest.kt` | `retryOperation` exponential backoff delays with `TestCoroutineScheduler` |
| `ios/IOSSimulatorParsingTest.kt` | `parseDeviceList`, `parseAppListFromPlist`, `isValidBundleId`, `isValidShellCommand` |
| `ios/IOSSimulatorTest.kt` | `listDevices`, `getFirstAvailableDevice`, `listApps`, `getAppInfo`, `launchApp`, `executeShell`, `ensureDeviceBooted` (MockK'd ProcessExecutor) |
| `ios/ProcessExecutorTest.kt` | Exit codes, stdout capture, timeout handling, non-existent commands |
| `ios/IOSAutomationClientTest.kt` | JSON-RPC requests, `isServerRunning`, Gson serialization (MockWebServer) |
| `android/AndroidValidationTest.kt` | `isValidPackageName`, `validateForwardArgs`, `validateShellArgs`, `validateInstallArgs` |
| `android/AutomationClientTest.kt` | `sendRequest` POST/params/errors, `isServerRunning` health check (MockWebServer) |
| `config/AppConfigTest.kt` | Default config values and log level |
| `ToolFactoryHelpersTest.kt` | `extractProperty`, `extractPattern`, `formatAppInfo` |
| `ToolFactoryPathTest.kt` | `findProjectRoot` (settings.gradle discovery, depth limit, edge cases), `findAutomationServerApk` (env var, search roots, ordering) |

### Automation Server (`automation-server/src/test/java/com/example/automationserver/`)

| Test File | What It Tests |
|-----------|---------------|
| `jsonrpc/JsonRpcModelsTest.kt` | `JsonRpcError` factory methods, request/response defaults and field handling |
| `uiautomator/UiAutomatorModelsTest.kt` | All data classes, default values, enum entries (SwipeSpeed, SwipeDirection, SwipeDistance) |
| `config/ServerConfigPortTest.kt` | `isValidPort` boundary tests, constants |
| `uiautomator/XmlUtilsTest.kt` | `stripInvalidXMLChars` — invalid ranges replaced, valid chars preserved |

### iOS Automation Server (`ios-automation-server/IOSAutomationServerTests/`)

| Test File | What It Tests |
|-----------|---------------|
| `JsonRpcModelsTests.swift` | `JsonRpcRequest.parse` (valid/invalid/malformed JSON), error factory methods & codes, `toDictionary`, success/error responses |
| `AutomationModelsTests.swift` | All result model `toDictionary()` conversions (UiHierarchyResult, DeviceInfoResult, OperationResult, ElementResult, InteractiveElement, InteractiveElementsResult), enum raw values & properties (SwipeDirection, SwipeDistance, SwipeSpeed) |
| `HelpersTests.swift` | `escapeXML` (nil, special chars, multiple replacements), `boundsString` (CGRect to bounds string, fractional truncation), `intParam` (Int/Double/String coercion, missing keys, edge cases) |

See `.claude/unit-testing-strategy.md` for the full testing roadmap (Plans 1-7).

## Key Patterns

- All device operations use suspend functions with coroutine-based async
- Device list caching reduces ADB overhead (validity: 1000ms default)
- Retry logic with exponential backoff in `ErrorHandler.retryOperation()`
- Custom exception hierarchy in `Exceptions.kt` with platform-specific error codes
- Tool timeout wrapper: `runWithTimeout()` in ToolFactory (default: 10s, 30s for UI hierarchy)
- Automation Server uses Ktor/Netty for HTTP server with Gson serialization
- Template Method Pattern: `BaseUiAutomatorBridge` defines operations, subclasses provide `UiDevice`, `UiAutomation`, and display bounds
- Reflection-based hierarchy dumping via `UiDevice.getWindowRoots()` for Flutter app support
- Centralized constants in `AutomationConfig.kt` - no magic numbers

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `VISION_TEST_LOG_LEVEL` | `PRODUCTION` | `PRODUCTION`, `DEVELOPMENT`, `DEBUG` |
| `VISION_TEST_APK_PATH` | (auto-detected) | Explicit path to test APK |
| `VISIONTEST_DIR` | `~/.local/share/visiontest` | Override install directory (must be under `$HOME`) |

### Default Timeouts (in `config/AppConfig.kt`)

- ADB timeout: 5000ms
- Device cache validity: 1000ms
- Tool execution timeout: 10000ms

### Android Automation Server Defaults (in `config/AutomationConfig.kt`)

- Server port: 9008 (range: 1024-65535)
- ADB port forwarding: `adb forward tcp:9008 tcp:9008`

### iOS Automation Server Defaults (in `config/IOSAutomationConfig.kt`)

- Server port: 9009 (range: 1024-65535)
- No port forwarding needed (iOS simulators share Mac's network stack)

## Prerequisites

- JDK 17+
- macOS or Linux (arm64 or x86_64)
- Android Platform Tools (ADB) in PATH — for Android automation
- Xcode Command Line Tools — for iOS simulator support (macOS only)
- Android SDK — only needed for building the automation-server module from source

> **Quick start:** Users who just need the MCP server can run `curl -fsSL https://github.com/docer1990/visiontest/releases/latest/download/install.sh | bash` — only Java 17+ is required.

## Important Design Decisions

See `LEARNING.md` for detailed explanations of:
- Why we use instrumentation instead of services
- Template Method Pattern for UIAutomator bridge
- Reflection-based hierarchy dumping for Flutter support
- Why non-working code was deleted instead of deprecated
- Security best practices (command allowlists, JSON escaping)
