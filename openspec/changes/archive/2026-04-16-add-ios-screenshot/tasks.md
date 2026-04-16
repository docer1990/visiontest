## 1. iOS Automation Server (Swift)

- [x] 1.1 Add `ScreenshotResult` struct to `ios-automation-server/IOSAutomationServerUITests/Models/AutomationModels.swift` with fields `success: Bool`, `pngBase64: String?`, `error: String?` and a `toDictionary()` method following the pattern of `UiHierarchyResult`
- [x] 1.2 Add a `screenshot()` method to `XCUITestBridge` that calls `XCUIScreen.main.screenshot().pngRepresentation`, base64-encodes the bytes, and returns a populated `ScreenshotResult` (checks for empty data since the XCUITest calls don't throw)
- [x] 1.3 Add a `case "ui.screenshot":` branch to `JsonRpcServer.executeMethod` that calls `bridge.screenshot().toDictionary()`
- [x] 1.4 Add unit tests for `ScreenshotResult.toDictionary()` in `ios-automation-server/IOSAutomationServerTests/AutomationModelsTests.swift` covering success (pngBase64 present, no error) and failure (error present, no pngBase64) paths

## 2. Kotlin MCP Client

- [x] 2.1 Add a `suspend fun screenshot(): String` method to `app/src/main/kotlin/com/example/visiontest/ios/IOSAutomationClient.kt` that calls `sendRequest("ui.screenshot", null)` and returns the raw response

## 3. MCP Tool Registration

- [x] 3.1 In `app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt`, add a `registerScreenshot(scope)` call to `registerTools()` and implement a private `registerScreenshot` method that:
  - Registers tool name `ios_screenshot` with `timeoutMs = 30000`
  - Documents the optional `outputPath` parameter, default path under `VISIONTEST_DIR/screenshots/`, overwrite behavior, and the `ios_start_automation_server` prerequisite in the description
  - Checks `iosAutomationClient.isServerRunning()` and returns the standard "server not running" message if false
  - Calls `iosAutomationClient.screenshot()`, parses the JSON response to extract `result.pngBase64`, and returns an informative error if `success` is false or `pngBase64` is missing
- [x] 3.2 Resolve the output path: if the caller provided `outputPath`, use it as-is; otherwise build `./screenshots/ios_screenshot_<yyyyMMdd_HHmmss>.png` resolved against the MCP server's current working directory (the user's project), using `java.time.LocalDateTime` with a matching formatter — NOT under `VISIONTEST_DIR` which would incorrectly point at the visiontest install dir
- [x] 3.3 Create parent directories (`Files.createDirectories`), base64-decode the `pngBase64` string, and write the bytes atomically to the resolved path; return a success message containing the absolute path
- [x] 3.4 Add unit test `app/src/test/.../IOSScreenshotToolTest.kt` covering: (a) default path contains timestamped filename under screenshots/, (b) base64 is decoded correctly and written as bytes (compare against a known small PNG fixture), (c) parent directories are created when missing, (d) server-not-running short-circuits with no file write, (e) `success: false` in the response surfaces as an error and writes no file

## 4. Documentation

- [x] 4.1 Add an `ios_screenshot` row to the "UI Automation (iOS)" tool table in `CLAUDE.md` with a one-line description matching the tool registration
- [x] 4.2 Verify the new tool appears in the typical automation workflow section if needed (probably not, since screenshots are orthogonal to the tap/swipe flow) — confirmed: screenshots are orthogonal to the tap/swipe workflow, so no update needed there

## 5. Verification

- [x] 5.1 Run `./gradlew :app:test` and ensure all Kotlin tests (including the new `IOSScreenshotToolTest`) pass
- [x] 5.2 Run `xcodebuild test -project ios-automation-server/IOSAutomationServer.xcodeproj -scheme IOSAutomationServer -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:IOSAutomationServerTests` and ensure the new model test passes (used iPhone 17 — iPhone 16 not installed on this machine; all 69 tests pass including the 2 new `ScreenshotResult` tests)
- [x] 5.3 Manual end-to-end check: start the iOS automation server on a booted simulator, invoke `ios_screenshot` via the MCP server (or a direct JSON-RPC curl), and confirm the PNG file opens and renders the simulator's current display at native resolution — verified by user
