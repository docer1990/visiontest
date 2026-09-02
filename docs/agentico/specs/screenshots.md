# Screenshot capture

## Purpose

This capability captures the full Android device or iOS simulator display through the platform automation server, transports the PNG through JSON-RPC, and persists it on the host running VisionTest.

## Requirements

### Requirement: Both automation servers expose screenshot JSON-RPC

The Android and iOS automation servers MUST accept `ui.screenshot` through their existing `/jsonrpc` endpoint. A handled capture outcome MUST return a result object with `success` plus either a non-empty base64-encoded PNG in `pngBase64` or an error description; a failure thrown out of method execution MUST use the server's normal JSON-RPC error envelope.

#### Scenario: Android capture succeeds

- **Given** the Android instrumentation server can capture and compress the current display
- **When** it receives a JSON-RPC request for `ui.screenshot`
- **Then** it SHALL return `success: true` and a no-wrap base64 encoding of the PNG bytes

#### Scenario: iOS capture succeeds

- **Given** the iOS UI-test server obtains non-empty `XCUIScreen` PNG data
- **When** it receives a JSON-RPC request for `ui.screenshot`
- **Then** it SHALL return `success: true` and the base64-encoded PNG data

#### Scenario: Device-side capture fails

- **Given** Android returns no bitmap, PNG compression fails or produces no bytes, or iOS produces empty PNG data
- **When** `ui.screenshot` executes
- **Then** the result MUST have `success: false`, MUST include an error description, and MUST NOT include usable PNG data

### Requirement: Platform MCP tools use the shared host-side saver

The MCP server MUST expose `android_screenshot` and `ios_screenshot`. Each tool MUST first require its automation server to be reachable, call the platform client's `ui.screenshot` method, and pass the raw JSON-RPC response through the shared screenshot saver.

#### Scenario: MCP tool selection

- **Given** both platform registrars are registered
- **When** an MCP client lists tools
- **Then** `android_screenshot` and `ios_screenshot` SHALL each appear once with an input schema and description

#### Scenario: Automation server is stopped

- **Given** the selected automation server does not answer its health check
- **When** its screenshot tool or CLI facade is invoked
- **Then** the handler MUST fail before requesting `ui.screenshot` and MUST NOT write the target file

### Requirement: Output paths are resolved on the host

A nonblank `outputPath` or CLI `--output` value MUST be resolved as an absolute host path, with relative values interpreted against the process current working directory. An absent MCP `outputPath`, an omitted CLI `--output`, or an empty or whitespace-only string MUST select `screenshots/android_screenshot_<yyyyMMdd_HHmmss>.png` for Android or `screenshots/ios_screenshot_<yyyyMMdd_HHmmss>.png` for iOS, also resolved against the current working directory. Callers MUST NOT rely on explicit MCP JSON `null` as omission: the current MCP argument helper reads it as the literal path string `null`.

#### Scenario: Explicit output path

- **Given** the caller supplies a nonblank absolute or relative output path
- **When** capture succeeds
- **Then** the PNG SHALL be saved at that resolved absolute path and the returned success text SHALL contain the absolute path

#### Scenario: Absent or blank output path

- **Given** the MCP argument is absent, the CLI option is omitted, or the caller supplies an empty or whitespace-only string
- **When** either platform resolves the destination
- **Then** it SHALL use that platform's timestamped filename under the current working directory's `screenshots` directory

#### Scenario: Explicit MCP JSON null

- **Given** an MCP caller includes `outputPath` with JSON `null`
- **When** the current argument helper and screenshot saver resolve it
- **Then** they SHALL treat `null` as the literal relative filename and resolve it to `<current-working-directory>/null`

### Requirement: Host persistence replaces files without partial final output

The saver MUST create missing parent directories, decode the base64 before creating the destination, write to a sibling temporary file, and move that file over the target with `REPLACE_EXISTING`. It MUST request an atomic move and MUST fall back to a non-atomic replacing move only when the filesystem reports that atomic moves are unsupported.

#### Scenario: Missing parent and existing target

- **Given** the destination parent does not exist or the destination already contains a file
- **When** valid PNG data is persisted
- **Then** missing directories SHALL be created and the destination SHALL contain the new decoded bytes

#### Scenario: Atomic move is unsupported

- **Given** the PNG was written to a sibling temporary file and the filesystem rejects `ATOMIC_MOVE` as unsupported
- **When** the saver finalizes the capture
- **Then** it SHALL retry with a non-atomic `REPLACE_EXISTING` move and SHALL leave no temporary sidecar after success

### Requirement: Invalid server responses do not create screenshots

Malformed JSON, malformed JSON-RPC result or error shapes, `success: false`, missing or empty `pngBase64`, invalid base64, and file-system failures MUST produce descriptive `Screenshot failed` text and MUST NOT leave a new completed screenshot. JSON-RPC error code `-32601` MUST identify an outdated Android APK or iOS bundle and advise rebuilding or updating it.

#### Scenario: Invalid base64

- **Given** a server returns `success: true` with a `pngBase64` value that cannot be decoded
- **When** the host processes the response
- **Then** it SHALL return text identifying invalid base64 PNG data and SHALL NOT write the destination

#### Scenario: Installed server is outdated

- **Given** an installed automation server returns JSON-RPC code `-32601` for `ui.screenshot`
- **When** the host processes the response
- **Then** it SHALL return platform-specific outdated-artifact guidance and SHALL NOT write the destination

#### Scenario: Failure text reaches the CLI

- **Given** screenshot processing returns failure text rather than throwing an exception
- **When** the `screenshot` CLI command delegates to that handler
- **Then** the CLI SHALL treat the returned string as normal stdout with exit code 0; only thrown failures use stderr and a nonzero exit code

## Verification

- Production JSON-RPC and capture: `automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt`, `automation-server/src/main/java/com/example/automationserver/uiautomator/BaseUiAutomatorBridge.kt`, `automation-server/src/main/java/com/example/automationserver/uiautomator/UiAutomatorModels.kt`, `ios-automation-server/IOSAutomationServerUITests/Server/JsonRpcServer.swift`, `ios-automation-server/IOSAutomationServerUITests/Bridge/XCUITestBridge.swift`, `ios-automation-server/IOSAutomationServerUITests/Models/AutomationModels.swift`
- Production clients and persistence: `app/src/main/kotlin/com/example/visiontest/android/AutomationClient.kt`, `app/src/main/kotlin/com/example/visiontest/ios/IOSAutomationClient.kt`, `app/src/main/kotlin/com/example/visiontest/tools/ScreenshotSaver.kt`, `app/src/main/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrar.kt`, `app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/ScreenshotCommand.kt`
- Executable tests: `app/src/test/kotlin/com/example/visiontest/tools/AndroidScreenshotToolTest.kt`, `app/src/test/kotlin/com/example/visiontest/tools/IOSScreenshotToolTest.kt`, `app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt`, `automation-server/src/test/java/com/example/automationserver/uiautomator/UiAutomatorModelsTest.kt`, `ios-automation-server/IOSAutomationServerTests/AutomationModelsTests.swift`
