## ADDED Requirements

### Requirement: MCP tool captures iOS simulator screenshot as PNG
The MCP server SHALL expose a tool named `ios_screenshot` that captures the current booted iOS simulator display and saves the image as a PNG file on the host filesystem.

#### Scenario: Screenshot saved to caller-supplied path
- **WHEN** an agent invokes `ios_screenshot` with parameter `outputPath` set to an absolute path ending in `.png`
- **THEN** the tool writes the PNG bytes of the current simulator display to that exact path and returns a success message containing the absolute path

#### Scenario: Screenshot saved to default path when outputPath is omitted
- **WHEN** an agent invokes `ios_screenshot` with no `outputPath` parameter
- **THEN** the tool writes the PNG to `./screenshots/ios_screenshot_<yyyyMMdd_HHmmss>.png` resolved against the MCP server's current working directory (the user's project when launched by a coding agent, NOT the visiontest install directory) and returns the absolute path of the saved file

#### Scenario: Parent directories are created automatically
- **WHEN** `ios_screenshot` is invoked with an `outputPath` whose parent directory does not yet exist
- **THEN** the tool creates all missing parent directories before writing the PNG

#### Scenario: Server not running
- **WHEN** `ios_screenshot` is invoked and the iOS automation server is not reachable on its configured port
- **THEN** the tool returns an error message instructing the caller to run `ios_start_automation_server` and does NOT write any file

### Requirement: iOS automation server exposes `ui.screenshot` JSON-RPC method
The iOS automation server SHALL accept a JSON-RPC request with method `ui.screenshot` that captures the current screen via XCUITest and returns the image bytes as a base64-encoded PNG string.

#### Scenario: Successful screenshot capture
- **WHEN** a JSON-RPC client POSTs `{"jsonrpc":"2.0","method":"ui.screenshot","id":1}` to `/jsonrpc` while the simulator is displaying content
- **THEN** the response `result` object contains `success: true` and a non-empty `pngBase64` string whose decoded bytes start with the PNG magic header (`89 50 4E 47 0D 0A 1A 0A`)

#### Scenario: Screenshot capture failure is reported
- **WHEN** the XCUITest screenshot API throws an error during capture
- **THEN** the response `result` object contains `success: false`, omits `pngBase64`, and includes an `error` field describing the failure

#### Scenario: Unknown method rejected
- **WHEN** a client calls the JSON-RPC endpoint with a misspelled method name resembling `ui.screenshot`
- **THEN** the server returns a JSON-RPC error with code `methodNotFound`

### Requirement: IOSAutomationClient provides a screenshot API
The Kotlin `IOSAutomationClient` SHALL expose a suspend function `screenshot()` that calls the `ui.screenshot` JSON-RPC method and returns the server's raw JSON response string, consistent with other client methods.

#### Scenario: screenshot() delegates to sendRequest
- **WHEN** `IOSAutomationClient.screenshot()` is called
- **THEN** it invokes `sendRequest("ui.screenshot", null)` and returns the resulting response string unchanged

### Requirement: Tool description documents outputPath semantics
The `ios_screenshot` tool description SHALL document the optional `outputPath` parameter, the default output location, the overwrite behavior when the path already exists, and the requirement that `ios_start_automation_server` be called first.

#### Scenario: Tool description includes required workflow
- **WHEN** the MCP client lists the `ios_screenshot` tool
- **THEN** the returned description mentions `outputPath` (optional), the default path `./screenshots/` relative to the server's working directory (user's current project), that existing files at the target path will be overwritten, and the prerequisite of a running iOS automation server
