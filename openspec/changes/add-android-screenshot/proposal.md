## Why

VisionTest recently gained an `ios_screenshot` MCP tool for capturing the iOS simulator display, but the equivalent capability does not yet exist for Android devices. Agents automating Android apps still have no way to capture a pixel-accurate image of the current screen for visual verification (layout regressions, rendering bugs, image assets, or UI that the accessibility tree does not describe). This change closes that gap by adding the Android counterpart, following the same shape as the iOS tool so agents can operate on both platforms with a consistent mental model.

## What Changes

- Add a new MCP tool `android_screenshot` that captures the connected Android device's current screen and saves it as a PNG to a caller-specified (or defaulted) path on the host filesystem.
- Extend the Android automation server (UIAutomator-based) with a new JSON-RPC method `ui.screenshot` that returns the PNG bytes as a base64-encoded string. Implementation uses `UiAutomation.takeScreenshot()` plus `Bitmap.compress(PNG, ...)` rather than `UiDevice.takeScreenshot(File)` so the capture never has to materialize a file on the device before crossing the ADB port forward.
- Extend `AutomationClient` (Kotlin MCP side) with a `screenshot()` suspend function that calls the new JSON-RPC method.
- The tool decodes the base64 payload and writes the PNG bytes atomically to the resolved output path, returning the absolute path in the tool result. Default path is `./screenshots/android_screenshot_<yyyyMMdd_HHmmss>.png` resolved against the MCP server's working directory (the user's project).
- Reuse the exact error-handling, path-resolution, and atomic-write logic already proven on the iOS side so both tools behave identically.

## Capabilities

### New Capabilities
- `android-screenshot`: Captures a screenshot of the connected Android device (physical or emulator) via the UIAutomator-based automation server and saves it as a PNG file on the host.

### Modified Capabilities
<!-- None: this change introduces a new capability without altering existing spec-level behavior. The ios-screenshot capability is untouched; this is its Android twin. -->

## Impact

- **Android automation server (`automation-server/`)** — New `screenshot()` method on `BaseUiAutomatorBridge` (shared code), new `ScreenshotResult` data class in `UiAutomatorModels.kt`, new `ui.screenshot` branch in `JsonRpcServerInstrumented.executeMethod`. Requires a new release of the test APK (`automation-server-debug-androidTest.apk`) for users who installed via `install.sh`.
- **MCP server (`app/`)** — New `screenshot()` method on `AutomationClient`, new tool registration in `AndroidAutomationToolRegistrar`. No changes to shared infrastructure (`ToolFactory`, `ToolScope`, `ErrorHandler`). The base64 decode / path resolution / atomic write logic is near-identical to `IOSAutomationToolRegistrar.captureScreenshot`; we duplicate it for now (shared helper extraction is out of scope) but keep behavior identical.
- **Tests** — New pure-JVM unit test `AndroidScreenshotToolTest.kt` covering base64 decode, default path, atomic write, server-not-running, and JSON-RPC error paths (parallels `IOSScreenshotToolTest`). New unit test for `ScreenshotResult` in `automation-server` test module.
- **Docs** — `CLAUDE.md` tool table needs a new `android_screenshot` row under "UI Automation (Android)".
- **External surface** — New JSON-RPC method on the Android automation server. No breaking changes to existing tools or endpoints. Agents using an older pre-built test APK will get a `methodNotFound` error that the Kotlin tool translates into an "outdated bundle, rebuild/update" hint.
