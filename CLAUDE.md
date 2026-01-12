# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Run Commands

```bash
# Build entire project
./gradlew build

# === MCP Server (app module) ===
./gradlew run                           # Run the MCP server
./gradlew test                          # Run all tests
./gradlew test --tests "AndroidTest"    # Run a specific test class
./gradlew shadowJar                     # Build fat JAR -> app/build/libs/visiontest.jar

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
```

## Architecture Overview

VisionTest is an MCP (Model Context Protocol) server enabling AI agents to interact with mobile devices. It consists of two modules:

### MCP Server (`app/`)

Kotlin/JVM server using stdio transport. Key files:
- `Main.kt` - Entry point, initializes managers and connects via stdio
- `ToolFactory.kt` - Registers all MCP tools (Android, iOS, and Automation)
- `android/Android.kt` - ADB communication via Adam library
- `android/AutomationClient.kt` - HTTP client for Automation Server JSON-RPC
- `config/AutomationConfig.kt` - Centralized constants for automation
- `ios/IOSManager.kt` - iOS simulator operations via `xcrun simctl`
- `common/DeviceConfig.kt` - Shared interface for both platforms

### Automation Server (`automation-server/`)

Native Android app providing UIAutomator access via JSON-RPC. Uses **instrumentation pattern** (like Maestro/Appium) for secure, privileged access.

**Main App (`src/main/`) - Configuration UI only:**
- `MainActivity.kt` - Shows setup instructions and port configuration
- `config/ServerConfig.kt` - SharedPreferences for port setting
- `jsonrpc/JsonRpcModels.kt` - Request/Response/Error data classes
- `uiautomator/BaseUiAutomatorBridge.kt` - Abstract base class with all UIAutomator logic
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
| `ui.click` | `x`, `y` | Click at coordinates |
| `ui.findElement` | `text`, `resourceId`, etc. | Find UI element |
| `device.getInfo` | - | Get display size, rotation, SDK |
| `device.pressBack` | - | Press back button |
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

**Typical Automation Workflow:**
1. `install_automation_server` - Install both APKs (one-time)
2. `start_automation_server` - Start JSON-RPC server via `am instrument`
3. `get_ui_hierarchy` - Retrieve UI elements to analyze the screen
4. `find_element` - Find specific elements by selector

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

## Key Patterns

- All device operations use suspend functions with coroutine-based async
- Device list caching reduces ADB overhead (validity: 1000ms default)
- Retry logic with exponential backoff in `ErrorHandler.retryOperation()`
- Custom exception hierarchy in `Exceptions.kt` with platform-specific error codes
- Tool timeout wrapper: `runWithTimeout()` in ToolFactory (default: 10s, 30s for UI hierarchy)
- Automation Server uses Ktor/Netty for HTTP server with Gson serialization
- Template Method Pattern: `BaseUiAutomatorBridge` defines operations, subclasses provide `UiDevice`
- Centralized constants in `AutomationConfig.kt` - no magic numbers

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `VISION_TEST_LOG_LEVEL` | `PRODUCTION` | `PRODUCTION`, `DEVELOPMENT`, `DEBUG` |
| `VISION_TEST_APK_PATH` | (auto-detected) | Explicit path to test APK |

### Default Timeouts (in `config/AppConfig.kt`)

- ADB timeout: 5000ms
- Device cache validity: 1000ms
- Tool execution timeout: 10000ms

### Automation Server Defaults (in `config/AutomationConfig.kt`)

- Server port: 9008 (range: 1024-65535)
- ADB port forwarding: `adb forward tcp:9008 tcp:9008`

## Prerequisites

- JDK 17+
- Android Platform Tools (ADB) in PATH
- Xcode Command Line Tools (for iOS simulator support)
- Android SDK (for automation-server module)

## Important Design Decisions

See `LEARNING.md` for detailed explanations of:
- Why we use instrumentation instead of services
- Template Method Pattern for UIAutomator bridge
- Why non-working code was deleted instead of deprecated
- Security best practices (command allowlists, JSON escaping)
