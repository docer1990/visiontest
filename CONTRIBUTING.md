# Contributing to VisionTest

This guide is for changing VisionTest itself. For installing or using the
product, start with [README.md](README.md). Repository guidance for coding
agents lives in [AGENTS.md](AGENTS.md); the skill distributed into client
projects lives in
[app/src/main/resources/agent-instructions.md](app/src/main/resources/agent-instructions.md).
Behavioral contracts live under [docs/agentico/specs/](docs/agentico/specs/).

Before changing Kotlin MCP or CLI code under `app/src/`, read
[kotlin-mcp-server.instruction.md](kotlin-mcp-server.instruction.md). Preserve
the shared registrar operations used by MCP and CLI, and update the behavioral
specification whenever public behavior changes.

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

1. **MCP Server and CLI** (`app/`) — Kotlin/JVM application that exposes mobile automation through Model Context Protocol (stdio transport) and 22 CLI subcommands
2. **Android Automation Server** (`automation-server/`) — Native Android app with UIAutomator API access via JSON-RPC, using the instrumentation pattern (like Maestro/Appium)
3. **iOS Automation Server** (`ios-automation-server/`) — Native iOS app with XCUITest access via JSON-RPC

### Project Structure

```
visiontest/
├── app/                              # MCP Server (Kotlin/JVM)
│   └── src/main/kotlin/com/example/visiontest/
│       ├── Main.kt                   # Entry point (MCP server or CLI dispatch)
│       ├── ToolFactory.kt            # Thin coordinator wiring registrars
│       ├── cli/
│       │   ├── VisionTestCli.kt      # Root Clikt command with 22 subcommands
│       │   ├── CliErrorHandler.kt    # Exit-code mapping + runCliCommand
│       │   ├── CliExit.kt            # CliExit exception + ExitCode enum
│       │   ├── PlatformOption.kt     # Platform enum + --platform option helpers
│       │   ├── ComponentHolder.kt    # Lazy DI graph for CLI commands
│       │   └── commands/             # Clikt subcommand adapters
│       ├── tools/
│       │   ├── ToolDsl.kt            # ToolScope DSL + CallToolRequest helpers
│       │   ├── ToolRegistrar.kt      # Interface for modular registration
│       │   ├── ToolHelpers.kt        # Pure utility functions
│       │   ├── AndroidDeviceToolRegistrar.kt
│       │   ├── AndroidAutomationToolRegistrar.kt
│       │   ├── AndroidStopToolRegistrar.kt
│       │   ├── AndroidWaitToolRegistrar.kt
│       │   ├── IOSDeviceToolRegistrar.kt
│       │   ├── IOSAutomationToolRegistrar.kt
│       │   └── IOSWaitToolRegistrar.kt
│       ├── discovery/
│       │   └── ToolDiscovery.kt      # APK, Xcode project, xctestrun discovery
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

`VisionTestCli` registers 22 subcommands. The authoritative command and argument
contract is [docs/agentico/specs/cli.md](docs/agentico/specs/cli.md); use
`visiontest --help` to inspect the built artifact. CLI adapters delegate to the
same registrar operations exposed through MCP.

`ToolFactory` composes the seven registrars shown above: `AndroidDeviceToolRegistrar`, `AndroidAutomationToolRegistrar`, `AndroidStopToolRegistrar`, `AndroidWaitToolRegistrar`, `IOSDeviceToolRegistrar`, `IOSAutomationToolRegistrar`, and `IOSWaitToolRegistrar`.

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
| `ui.swipeOnElement` | `direction`, selector, `speed` | Yes | Yes |
| `ui.findElement` | `text`, `resourceId`, etc. | Yes | Yes |
| `ui.getInteractiveElements` | `includeDisabled` | Yes | Yes |
| `ui.screenshot` | - | Yes | Yes |
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
| `stop_automation_server` | Force-stop server processes and remove port forwarding (idempotent) |
| `automation_server_status` | Check if server is running |
| `get_ui_hierarchy` | Get XML of all visible UI elements |
| `get_interactive_elements` | Get filtered list of interactive elements |
| `find_element` | Find element by text, resourceId, className, etc. |
| `wait_for_element` | Poll until an element appears (optional `timeoutMs`, max 30s) |
| `wait_until_gone` | Poll until an element disappears (spinners, dialogs) |
| `android_tap_by_coordinates` | Tap at screen coordinates |
| `android_swipe` | Swipe by coordinates |
| `android_swipe_direction` | Swipe by direction with distance and speed |
| `android_swipe_on_element` | Swipe on a specific element |
| `android_get_device_info` | Get display size, rotation, SDK version |
| `android_input_text` | Type text into the currently focused element |
| `android_press_back` | Press the back button |
| `android_press_home` | Press the home button |
| `android_screenshot` | Capture a PNG and save it on the host |

### UI Automation (iOS)

| Tool | Description |
|------|-------------|
| `ios_start_automation_server` | Start XCUITest server (pre-built bundle or source build) |
| `ios_automation_server_status` | Check if server is running |
| `ios_get_ui_hierarchy` | Get XML of all visible UI elements |
| `ios_get_interactive_elements` | Get filtered list of interactive elements |
| `ios_find_element` | Find element by text, identifier, etc. |
| `ios_wait_for_element` | Poll until an element appears (optional `timeoutMs`, max 30s) |
| `ios_wait_until_gone` | Poll until an element disappears (spinners, sheets) |
| `ios_tap_by_coordinates` | Tap at screen coordinates |
| `ios_swipe` | Swipe by coordinates |
| `ios_swipe_direction` | Swipe by direction with distance and speed |
| `ios_swipe_on_element` | Swipe inside a selected element |
| `ios_get_device_info` | Get display size, rotation, iOS version |
| `ios_input_text` | Type text into the currently focused element |
| `ios_press_home` | Press home button |
| `ios_stop_automation_server` | Stop the running XCUITest server |
| `ios_screenshot` | Capture a PNG and save it on the host |

## Testing

### Running Tests

```bash
# Run all Gradle unit tests (MCP server + Android automation server)
./gradlew test

# Run only MCP server (app/) tests
./gradlew :app:test

# Run only Android automation server tests
./gradlew :automation-server:test

# Run a specific app test class (use the task that owns the test)
./gradlew :app:test --tests "ErrorHandlerTest"

# Run packaged-JAR end-to-end tests (builds and launches the fat JAR)
./gradlew :app:e2eTest

# Run iOS automation server unit tests
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerTests
```

The Gradle `test` tasks run pure JVM unit tests (no device or emulator required). The separate `:app:e2eTest` task assembles the fat JAR and launches it in subprocesses to verify packaged-JAR behavior; it also requires no device or emulator. iOS tests run on the simulator but don't need a separately running automation server.

### Test Coverage

| Module | Test File | Coverage Area |
|--------|-----------|---------------|
| `app/` | `MainDispatchTest.kt` | CLI versus MCP server routing |
| `app/` | `McpStdioE2ETest.kt` | Packaged JAR handshake and exact MCP tool contract |
| `app/` | `ToolFactoryHelpersTest.kt` | Tool output parsing and formatting helpers |
| `app/` | `ToolFactoryPathTest.kt` | Project, APK, iOS bundle, and source-project discovery |
| `app/android` | `AndroidValidationTest.kt` | Package-name and ADB argument validation |
| `app/android` | `AutomationClientTest.kt` | Android JSON-RPC requests and health checks |
| `app/android` | `AutomationClientWaitTest.kt` | Android element polling, timeouts, and malformed responses |
| `app/cli` | `CliCommandIntegrationTest.kt` | CLI delegation, waits, screenshots, stop behavior, and exit codes |
| `app/cli` | `CliErrorHandlerTest.kt` | CLI exception-to-exit-code mapping |
| `app/cli` | `InitCommandE2ETest.kt` | Packaged JAR agent-instruction installation |
| `app/cli` | `InitCommandTest.kt` | Agent selection, paths, validation, and idempotent writes |
| `app/cli` | `ParityCliTest.kt` | Six parity commands, selectors, JSON output, and platform errors |
| `app/cli` | `VisionTestCliTest.kt` | Subcommand contract and platform argument parsing |
| `app/config` | `AppConfigTest.kt` | Default application configuration |
| `app/ios` | `IOSAutomationClientTest.kt` | iOS JSON-RPC requests and health checks |
| `app/ios` | `IOSSimulatorParsingTest.kt` | Simulator JSON, plist, bundle ID, and shell validation |
| `app/ios` | `IOSSimulatorTest.kt` | Simulator operations with a mocked process executor |
| `app/ios` | `ProcessExecutorTest.kt` | Process output, errors, and timeouts |
| `app/tools` | `AndroidAutomationToolRegistrarTest.kt` | Android automation handlers and validation |
| `app/tools` | `AndroidDeviceToolRegistrarTest.kt` | Android device tool handlers |
| `app/tools` | `AndroidScreenshotToolTest.kt` | Android screenshot paths, persistence, and error handling |
| `app/tools` | `AndroidStopToolRegistrarTest.kt` | Idempotent stop and port-forward cleanup |
| `app/tools` | `AndroidWaitToolRegistrarTest.kt` | Android wait-tool selectors and timeout bounds |
| `app/tools` | `IOSDeviceToolRegistrarTest.kt` | iOS device tool handlers |
| `app/tools` | `IOSSwipeToolRegistrarTest.kt` | iOS element-swipe schema, validation, and delegation |
| `app/tools` | `IOSScreenshotToolTest.kt` | iOS screenshot paths, persistence, and error handling |
| `app/tools` | `IOSWaitToolRegistrarTest.kt` | iOS wait-tool selectors and timeout bounds |
| `app/tools` | `ToolDslTest.kt` | Tool registration DSL argument extraction |
| `app/utils` | `ErrorHandlerCoroutineTest.kt` | Retry timing and cancellation |
| `app/utils` | `ErrorHandlerTest.kt` | MCP error mapping and retry outcomes |
| `automation-server/config` | `ServerConfigPortTest.kt` | Port validation boundaries |
| `automation-server/jsonrpc` | `JsonRpcModelsTest.kt` | JSON-RPC error factories and model defaults |
| `automation-server/uiautomator` | `BaseUiAutomatorBridgeFilterTest.kt` | Interactive-element visibility and filtering |
| `automation-server/uiautomator` | `UiAutomatorModelsTest.kt` | Automation result models and defaults |
| `automation-server/uiautomator` | `XmlUtilsTest.kt` | Invalid XML character replacement |
| `automation-server/androidTest` | `AutomationServerTest.kt` | Long-running instrumentation JSON-RPC server entry point |
| `ios-automation-server/` | `AutomationModelsTests.swift` | Result dictionaries and enum values |
| `ios-automation-server/` | `HelpersTests.swift` | XML, bounds, and parameter helpers |
| `ios-automation-server/` | `JsonRpcModelsTests.swift` | JSON-RPC parsing, error factories, and codes |
| `ios-automation-server/` | `AutomationServerUITest.swift` | Long-running XCUITest JSON-RPC server entry point |

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
visiontest stop_automation_server -p android
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

### Testing the Installer

You can test `install.sh` locally without publishing a release using `--local-jar`:

```bash
# Build the fat JAR first
./gradlew shadowJar

# Create and validate an isolated HOME; keep these commands in one shell
INSTALL_TEST_BASE="${TMPDIR:-/tmp}"
INSTALL_TEST_BASE="${INSTALL_TEST_BASE%/}"
INSTALL_TEST_ROOT=$(mktemp -d "$INSTALL_TEST_BASE/visiontest-installer-test.XXXXXX") || exit 1
case "$INSTALL_TEST_ROOT" in
  "$INSTALL_TEST_BASE"/visiontest-installer-test.??????) ;;
  *) printf 'Refusing unsafe temporary path: %s\n' "$INSTALL_TEST_ROOT" >&2; exit 1 ;;
esac
cleanup_installer_test() {
  case "${INSTALL_TEST_ROOT:-}" in
    "$INSTALL_TEST_BASE"/visiontest-installer-test.??????) rm -rf -- "$INSTALL_TEST_ROOT" ;;
    *) printf 'Refusing unsafe cleanup path: %s\n' "${INSTALL_TEST_ROOT:-}" >&2; return 1 ;;
  esac
}
trap cleanup_installer_test EXIT

INSTALL_TEST_HOME="$INSTALL_TEST_ROOT/home"
INSTALL_TEST_BIN="$INSTALL_TEST_HOME/.local/bin"
INSTALL_TEST_DATA="$INSTALL_TEST_HOME/.local/share/visiontest"
mkdir -p "$INSTALL_TEST_HOME"

# Run the installer without touching your real wrapper or shell profiles
HOME="$INSTALL_TEST_HOME" \
  VISIONTEST_DIR="$INSTALL_TEST_DATA" \
  PATH="$INSTALL_TEST_BIN:$PATH" \
  bash install.sh --local-jar "$PWD/app/build/libs/visiontest.jar"

# Verify it works
HOME="$INSTALL_TEST_HOME" PATH="$INSTALL_TEST_BIN:$PATH" \
  "$INSTALL_TEST_BIN/visiontest" --help
```

The `EXIT` trap removes only the validated temporary root. Local-JAR mode skips downloading the JAR, APKs, and iOS bundle from GitHub Releases — it copies your local build instead. Agent setup is no longer performed by `install.sh`; use `visiontest init --agent <agents>` to install agent instructions when needed.

## Extending VisionTest

### Adding New JSON-RPC Methods

1. Add the native operation to Android's `BaseUiAutomatorBridge.kt` or iOS's
   `XCUITestBridge.swift`.
2. Register it in Android's `JsonRpcServerInstrumented.kt` or iOS's
   `JsonRpcServer.swift`.
3. Add the matching Kotlin client method in `AutomationClient.kt` or
   `IOSAutomationClient.kt`.
4. Add focused native and Kotlin wire-contract tests.
5. Expose the operation through the appropriate registrar under `tools/`.

### Adding New MCP Tools

1. Add the tool to the appropriate registrar in `tools/` using the `ToolScope` DSL
2. Extract the handler body into an `internal suspend fun` on the registrar (for CLI reuse)
3. The MCP tool is automatically registered via `ToolFactory.registerAllTools()`
4. Update `McpStdioE2ETest.EXPECTED_TOOLS` to preserve the packaged MCP contract
5. Add a CLI adapter in `cli/commands/` when the capability belongs in the CLI,
   then register it in `VisionTestCli.kt`
6. Update the relevant behavioral specification and public documentation

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
- [Installation guide](docs/installation.md) — supported hosts, installed assets, and CLI setup
- [Release guide](docs/release.md) — local verification, publishing, and release recovery
- [Behavior specifications](docs/agentico/specs/) — current externally observable contracts
- [Technical decisions](docs/decisions/) — durable architectural choices and rationale
