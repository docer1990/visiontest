## 1. Android Automation Server (on-device)

- [x] 1.1 Add a `ScreenshotResult` data class to `automation-server/src/main/java/com/example/automationserver/uiautomator/UiAutomatorModels.kt` with fields `success: Boolean`, `pngBase64: String? = null`, `error: String? = null`, following the same pattern as `UiHierarchyResult`
- [x] 1.2 Add a `screenshot()` method to `BaseUiAutomatorBridge` (in `automation-server/src/main/java/com/example/automationserver/uiautomator/BaseUiAutomatorBridge.kt`) that calls `getUiAutomation().takeScreenshot()`, compresses the returned `Bitmap` to PNG via `Bitmap.compress(Bitmap.CompressFormat.PNG, 100, ByteArrayOutputStream())`, base64-encodes the bytes using `android.util.Base64.encodeToString(bytes, Base64.NO_WRAP)`, and returns a populated `ScreenshotResult`. Handle a null/empty bitmap by returning `ScreenshotResult(success = false, error = "Screenshot capture returned no bitmap (display unavailable or content is FLAG_SECURE)")`. Wrap the call in try/catch and return `success = false, error = e.message` on exception, same shape as `dumpHierarchy()`.
- [x] 1.3 Add a `case "ui.screenshot":` branch to `JsonRpcServerInstrumented.executeMethod` in `automation-server/src/androidTest/java/com/example/automationserver/JsonRpcServerInstrumented.kt` that calls `uiAutomator.screenshot()` and returns the `ScreenshotResult` (Gson will serialize it into the JSON-RPC `result` field, identical to how other bridge calls are returned)
- [x] 1.4 Add unit tests for `ScreenshotResult` to `automation-server/src/test/java/com/example/automationserver/uiautomator/UiAutomatorModelsTest.kt` covering: (a) success state with `pngBase64` populated and `error` null; (b) failure state with `error` populated and `pngBase64` null; (c) default values (`pngBase64` defaults to null, `error` defaults to null)

## 2. Kotlin MCP Client

- [x] 2.1 Add a `suspend fun screenshot(): String` method to `app/src/main/kotlin/com/example/visiontest/android/AutomationClient.kt` that calls `sendRequest("ui.screenshot", null)` and returns the raw response, mirroring the existing `getUiHierarchy()` shape

## 3. MCP Tool Registration

- [x] 3.1 In `app/src/main/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrar.kt`, add a `registerScreenshot(scope)` call to `registerTools()` and implement a private `registerScreenshot` method that:
  - Registers tool name `android_screenshot` with `timeoutMs = 30000`
  - Documents the optional `outputPath` parameter (absolute or CWD-relative, overwrites existing files, auto-creates parent dirs), the default path `./screenshots/android_screenshot_<yyyyMMdd_HHmmss>.png` relative to the MCP server's working directory (i.e. the user's project, NOT the visiontest install dir), and the `start_automation_server` prerequisite in the description
  - Checks `automationClient.isServerRunning()` first and returns the standard "Automation server is not running. Use 'start_automation_server' first." short-circuit if false
  - Calls `automationClient.screenshot()`, parses the JSON-RPC response with Gson's `JsonParser`, and surfaces the standard error cases (JSON-RPC `error` envelope with `-32601` → outdated-APK hint, other `error` envelopes → "Android automation server returned error (code): message", missing/non-object `result`, `success: false` → bridge-reported error, missing `pngBase64` → outdated-APK hint, invalid base64 → decode error, parse failure → "unable to parse response from Android automation server")
- [x] 3.2 Implement `internal fun resolveScreenshotPath(outputPath: String?): File` on the registrar (same shape as `IOSAutomationToolRegistrar.resolveScreenshotPath`): if the caller provided `outputPath` and it is non-blank, return `File(outputPath).absoluteFile`; otherwise build `File("screenshots/android_screenshot_$timestamp.png").absoluteFile` using `java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))`. Do NOT resolve against `VISIONTEST_DIR` — the default MUST land in the MCP server's CWD (the user's project).
- [x] 3.3 Implement `internal suspend fun writeScreenshot(target: File, pngBase64: String): String` on `Dispatchers.IO` that: base64-decodes (catching `IllegalArgumentException` → "invalid base64 PNG data" message), ensures the parent directory exists (`Files.createDirectories`), writes to a sibling temp file (`Files.createTempFile(parentDir, ".android_screenshot_", ".png.tmp")`), then `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` onto the target path (falling back to plain `REPLACE_EXISTING` on `AtomicMoveNotSupportedException`). On any `IOException` during write, delete the temp file and return a user-facing error. On success return `"Screenshot saved to ${target.absolutePath}"`. Mirror the iOS implementation exactly, swapping "iOS" → "Android" in error messages and the temp-file prefix.
- [x] 3.4 Add unit test `app/src/test/kotlin/com/example/visiontest/tools/AndroidScreenshotToolTest.kt` parallel to `IOSScreenshotToolTest`, using `MockWebServer` and `AutomationClient`. Cover:
  - `resolveScreenshotPath(null)` returns a file under `./screenshots/` with filename matching `android_screenshot_\d{8}_\d{6}\.png`, absolute, parent-parent equals `user.dir`
  - `resolveScreenshotPath(explicitPath)` returns the explicit path verbatim
  - `resolveScreenshotPath("   ")` (blank string) falls back to the default
  - `captureScreenshot(target)` writes the decoded PNG bytes to the target path when the server returns `success: true, pngBase64: <fixture>`
  - Missing parent directories are created
  - Server-not-running short-circuits, no file is written, and only the `/health` probe is made
  - `success: false` in the response surfaces as an error and writes no file
  - Missing `pngBase64` surfaces the outdated-APK hint
  - Missing/non-object `result` surfaces parse errors
  - Malformed JSON surfaces "unable to parse response from Android automation server"
  - JSON-RPC error envelope with code `-32601` maps to the outdated-APK hint mentioning `android_*`
  - Other JSON-RPC error envelopes surface code and message
  - Invalid base64 surfaces a decode error
  - Atomic write leaves no `.png.tmp` sidecar on success

## 4. Documentation

- [x] 4.1 Add an `android_screenshot` row to the "UI Automation (Android)" tool table in `CLAUDE.md` with a one-line description matching the tool registration (reference: the `ios_screenshot` row added in the previous change)
- [x] 4.2 Verify the new tool does not need to appear in the "Typical Automation Workflow" section (screenshots are orthogonal to the install→start→inspect→interact flow — should match the decision made for iOS); if any other docs reference the iOS tool side-by-side with Android tools, add the Android counterpart there

## 5. Verification

- [x] 5.1 Run `./gradlew :automation-server:test` and ensure the new `ScreenshotResult` tests in `UiAutomatorModelsTest` pass
- [x] 5.2 Run `./gradlew :app:test` and ensure the new `AndroidScreenshotToolTest` passes along with all existing Kotlin tests
- [x] 5.3 Run `./gradlew :automation-server:assembleDebug :automation-server:assembleDebugAndroidTest` and `./gradlew :automation-server:lint` to ensure the instrumentation module still compiles cleanly with the new JSON-RPC branch and the `BaseUiAutomatorBridge.screenshot()` method
- [ ] 5.4 Manual end-to-end check: build and install both APKs on a connected device/emulator, run `start_automation_server`, invoke `android_screenshot` via the MCP server (or a direct JSON-RPC curl to `localhost:9008/jsonrpc`), confirm the PNG file opens and renders the device's current display at native resolution
- [ ] 5.5 Manual cross-platform symmetry check: with both iOS and Android automation servers running, confirm `ios_screenshot` and `android_screenshot` produce files under the same `./screenshots/` directory with filenames differing only in the platform prefix
