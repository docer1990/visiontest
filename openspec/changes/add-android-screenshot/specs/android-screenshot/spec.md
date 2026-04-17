## ADDED Requirements

### Requirement: MCP tool captures Android device screenshot as PNG
The MCP server SHALL expose a tool named `android_screenshot` that captures the current display of the connected Android device (physical or emulator) and saves the image as a PNG file on the host filesystem.

#### Scenario: Screenshot saved to caller-supplied path
- **WHEN** an agent invokes `android_screenshot` with parameter `outputPath` set to an absolute path ending in `.png`
- **THEN** the tool writes the PNG bytes of the current Android display to that exact path and returns a success message containing the absolute path

#### Scenario: Screenshot saved to default path when outputPath is omitted
- **WHEN** an agent invokes `android_screenshot` with no `outputPath` parameter
- **THEN** the tool writes the PNG to `./screenshots/android_screenshot_<yyyyMMdd_HHmmss>.png` resolved against the MCP server's current working directory (the user's project when launched by a coding agent, NOT the visiontest install directory) and returns the absolute path of the saved file

#### Scenario: Blank outputPath falls back to the default
- **WHEN** `android_screenshot` is invoked with an `outputPath` consisting only of whitespace
- **THEN** the tool treats it as omitted and uses the default path described above

#### Scenario: Parent directories are created automatically
- **WHEN** `android_screenshot` is invoked with an `outputPath` whose parent directory does not yet exist
- **THEN** the tool creates all missing parent directories before writing the PNG

#### Scenario: Existing file at target path is overwritten
- **WHEN** `android_screenshot` is invoked with an `outputPath` that already contains a file
- **THEN** the tool overwrites the existing file with the new PNG bytes and returns a success message

#### Scenario: Atomic write leaves no temp file on success
- **WHEN** `android_screenshot` successfully writes a PNG to the target path
- **THEN** no temporary `.png.tmp` sidecar file remains in the target's parent directory after the tool returns

#### Scenario: Automation server not running
- **WHEN** `android_screenshot` is invoked and the Android automation server is not reachable on its configured port
- **THEN** the tool returns an error message instructing the caller to run `start_automation_server` first and does NOT write any file

#### Scenario: Server reports screenshot failure
- **WHEN** the automation server returns `{ "success": false, "error": "<message>" }` for the `ui.screenshot` method
- **THEN** the tool returns a failure message containing the server's error text and writes no file

#### Scenario: Server returns invalid base64 PNG data
- **WHEN** the automation server returns `success: true` but `pngBase64` cannot be decoded as base64
- **THEN** the tool returns an error message referencing invalid base64 PNG data and writes no file

#### Scenario: Outdated installed APK does not know ui.screenshot
- **WHEN** `android_screenshot` is invoked and the automation server responds with a JSON-RPC `methodNotFound` error (code `-32601`) for `ui.screenshot`
- **THEN** the tool returns an error message identifying this as an outdated Android automation server APK and instructing the caller to rebuild from source or update the installed APK, and writes no file

### Requirement: Android automation server exposes `ui.screenshot` JSON-RPC method
The Android automation server (instrumentation test process) SHALL accept a JSON-RPC request with method `ui.screenshot` that captures the current screen via UIAutomator and returns the image bytes as a base64-encoded PNG string.

#### Scenario: Successful screenshot capture
- **WHEN** a JSON-RPC client POSTs `{"jsonrpc":"2.0","method":"ui.screenshot","id":1}` to `/jsonrpc` while the device is displaying normal content
- **THEN** the response `result` object contains `success: true` and a non-empty `pngBase64` string whose decoded bytes start with the PNG magic header (`89 50 4E 47 0D 0A 1A 0A`)

#### Scenario: Screenshot capture failure is reported
- **WHEN** `UiAutomation.takeScreenshot()` returns a null or empty bitmap (e.g. unavailable display or FLAG_SECURE content blocks capture)
- **THEN** the response `result` object contains `success: false`, omits `pngBase64`, and includes an `error` field describing the failure

#### Scenario: Unknown method rejected
- **WHEN** a client calls the JSON-RPC endpoint with a misspelled method name resembling `ui.screenshot`
- **THEN** the server returns a JSON-RPC error with code `-32601` (methodNotFound) and the Kotlin tool surfaces the outdated-APK guidance described above

### Requirement: AutomationClient provides a screenshot API
The Kotlin `AutomationClient` SHALL expose a suspend function `screenshot()` that calls the `ui.screenshot` JSON-RPC method and returns the server's raw JSON response string, consistent with other client methods.

#### Scenario: screenshot() delegates to sendRequest
- **WHEN** `AutomationClient.screenshot()` is called
- **THEN** it invokes `sendRequest("ui.screenshot", null)` and returns the resulting response string unchanged

### Requirement: Tool description documents outputPath semantics and prerequisite
The `android_screenshot` tool description SHALL document the optional `outputPath` parameter, the default output location, the overwrite behavior when the path already exists, and the requirement that the automation server be running (via `start_automation_server`) before the tool is invoked.

#### Scenario: Tool description includes required workflow
- **WHEN** the MCP client lists the `android_screenshot` tool
- **THEN** the returned description mentions `outputPath` (optional), the default path `./screenshots/` relative to the server's working directory (user's current project), that existing files at the target path will be overwritten, and the prerequisite that `start_automation_server` must have been called first
