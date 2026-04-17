## Context

The Android automation server (`automation-server/`) is a UIAutomator-based JSON-RPC 2.0 server that runs as an Android instrumentation test. The MCP server (`app/`) talks to it via `AutomationClient`, which wraps `HttpURLConnection` calls to `/jsonrpc` on `localhost:9008` (ADB-forwarded). All existing JSON-RPC methods exchange text-based JSON payloads (XML hierarchies as strings, element data as objects).

Screenshots are binary PNG data — tens of KB to a few MB per image — and must cross three boundaries:

1. Android UiAutomation capture → instrumentation test process (on device).
2. Device (via ADB TCP forward) → Mac host (MCP server) over HTTP/JSON-RPC.
3. MCP server → caller's working directory as a PNG file.

Android exposes two relevant APIs:
- `UiDevice.takeScreenshot(File)` — writes a PNG directly to a file path on the device (must then be `adb pull`-ed to host).
- `UiAutomation.takeScreenshot()` — returns a `Bitmap` in the test process memory.

The recently-merged `ios_screenshot` tool (archived change `2026-04-16-add-ios-screenshot`) established the shape: base64-over-JSON-RPC, host-side decode/write, optional `outputPath` defaulting to `./screenshots/<prefix>_<timestamp>.png`. This change adopts the same pattern for consistency.

## Goals / Non-Goals

**Goals:**
- Capture the current Android screen as a PNG using the existing UIAutomator bridge.
- Return the file path of the saved PNG to the MCP caller, so the agent can reference it in follow-up operations.
- Allow the caller to specify an output path; default to a sensible location (user's project CWD) when omitted.
- Keep the transport mechanism consistent with existing JSON-RPC methods (no new endpoints, no `adb pull`).
- Fail clearly when the server is not running, when the output path is unwritable, when the screenshot call itself fails, or when the installed test APK is too old to know `ui.screenshot`.
- Behave identically to `ios_screenshot` from the agent's perspective (same default path shape, same error phrasing) so cross-platform automation scripts stay symmetric.

**Non-Goals:**
- Capturing a specific view/subregion — only the full display.
- Image format options (JPEG, compression level, resizing) — PNG at native resolution only.
- Returning the image inline as an MCP image content block — file-on-disk only (matches iOS).
- Streaming or chunked transport for very large screenshots.
- Sharing a common `ScreenshotToolSupport` helper with the iOS registrar. The two registrars each get their own implementation now; a refactor to share the host-side write logic can happen later as a non-breaking cleanup.
- Screenshotting a specific Android device when multiple are connected — the automation server targets the single device that `adb` is forwarded to, same as every other tool.

## Decisions

### Decision 1: Encode PNG as base64 over the existing JSON-RPC transport

**Choice:** The new `ui.screenshot` JSON-RPC method returns `{ "success": true, "pngBase64": "<base64 string>" }`. The Kotlin client decodes and writes to disk.

**Alternatives considered:**
- **`UiDevice.takeScreenshot(File)` on device + `adb pull` on host**: Avoids base64 overhead (~33%) but requires knowing a writable path on the device, a second ADB call, and splits the transport across two mechanisms. It also bypasses the JSON-RPC "server is the single source of truth" contract every other tool relies on.
- **Separate binary HTTP endpoint (`GET /screenshot`)**: Avoids base64 overhead but doubles the surface area of the Ktor server, requires new routing/content-type handling, and diverges from the single-method JSON-RPC dispatch pattern established by every other operation.

**Rationale:** Base64-over-JSON-RPC preserves architectural consistency across both automation servers (iOS and Android), and the size overhead is acceptable at typical phone-screen resolutions (~2–4 MB base64 for a 1080×2400 screen). It also matches exactly what `ios_screenshot` does — the Kotlin tool code on the host is ~95% identical, which is a strong signal that the transport choice is right.

### Decision 2: Use `UiAutomation.takeScreenshot()` + in-memory `Bitmap.compress(PNG)`, not `UiDevice.takeScreenshot(File)`

**Choice:** The Swift-side equivalent was `XCUIScreen.main.screenshot().pngRepresentation`. On Android the equivalent is `uiAutomation.takeScreenshot()` (returns a `Bitmap`) followed by `bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)` into a `ByteArrayOutputStream`, then base64-encode the bytes.

**Alternatives considered:**
- **`UiDevice.takeScreenshot(File)`**: Writes PNG directly to a device path, which we'd then have to read back into memory (or `adb pull` to host). Extra filesystem I/O on the device for no benefit. Also: the test process's filesystem sandbox can be finicky, and we'd need to pick/cleanup a temp path on the device.

**Rationale:** Keeping the entire capture in-memory on the device matches the iOS flow (`.pngRepresentation` is also in-memory) and avoids any device-side filesystem concerns. `UiAutomation.takeScreenshot()` is also the API `UiDevice.takeScreenshot` delegates to internally.

### Decision 3: File is written by the Kotlin MCP tool, not by the device side

**Choice:** The UiAutomator bridge returns only the base64-encoded PNG bytes. The Kotlin tool handler on the host decodes and writes the file to the caller's specified (or default) path.

**Rationale:** Same reasoning as iOS — writing on the host keeps path semantics unambiguous (caller provides a host path, host writes it). Paths like `./screenshots/` resolve against the MCP server's CWD (the user's project), which would make no sense on the Android device's sandboxed filesystem.

### Decision 4: Output path parameter — optional, default is CWD-relative with `android_screenshot_` prefix

**Choice:** `outputPath` is optional. When omitted, default to `./screenshots/android_screenshot_<yyyyMMdd_HHmmss>.png` resolved against the MCP server's current working directory. When provided, the path is used as-is (both absolute and CWD-relative paths are supported). Missing parent directories are created.

**Rationale:** Matches iOS behavior exactly, except the filename prefix changes from `ios_screenshot_` to `android_screenshot_` so the two platforms' outputs don't collide when an agent runs both. Coding agents (Claude Code, Codex, etc.) launch MCP servers with CWD set to the project they're working on, so the CWD-relative default lands the PNG in that project, where the agent and user can easily reference it.

### Decision 5: New `ScreenshotResult` data class mirroring existing result types

**Choice:** Add `ScreenshotResult(success: Boolean, pngBase64: String?, error: String?)` to `automation-server/src/main/java/com/example/automationserver/uiautomator/UiAutomatorModels.kt`. It follows the same pattern as `UiHierarchyResult`.

**Rationale:** Consistency with existing models. These data classes are already covered by unit tests in `UiAutomatorModelsTest.kt`, so the new one plugs into the existing test pattern.

### Decision 6: Tool name is `android_screenshot`

**Choice:** The MCP tool name is `android_screenshot`, matching the `android_*` prefix pattern used by `android_tap_by_coordinates`, `android_swipe`, `android_swipe_direction`, `android_input_text`, etc.

**Alternatives considered:**
- `screenshot_android` — out of order with the existing `android_*` convention.
- Platform-less `screenshot` — ambiguous when both iOS and Android tools are registered.

**Rationale:** `android_screenshot` matches the prefix convention that's already established in `AndroidAutomationToolRegistrar` and mirrors `ios_screenshot`.

### Decision 7: Timeout and registration

**Choice:** Register in `AndroidAutomationToolRegistrar` alongside the other Android tools. Use `timeoutMs = 30000` (same as `get_ui_hierarchy` and matching the iOS screenshot timeout).

**Rationale:** `UiAutomation.takeScreenshot()` typically returns in <1s but PNG compression of a 1080×2400 bitmap plus base64 encode + HTTP transport warrants the same headroom we gave to UI hierarchy dumps.

### Decision 8: Duplicate host-side write/decode logic instead of extracting a helper

**Choice:** Copy the ~40 lines of base64 decode + atomic-write + path-resolution logic from `IOSAutomationToolRegistrar` into `AndroidAutomationToolRegistrar`, renaming `ios_screenshot_` prefix to `android_screenshot_` and keeping error messages platform-specific ("Android automation server" vs "iOS automation server").

**Alternatives considered:**
- **Extract a `ScreenshotWriter` helper now**: Cleaner but balloons the scope of this change, and the right shape of the helper is clearer after two concrete callers exist. Deferring also lets us land the Android tool without touching the stable iOS code path.

**Rationale:** "Three similar lines is better than a premature abstraction." Two concrete implementations will make the eventual shared helper obvious; a shared helper designed from one caller's perspective tends to leak its platform assumptions. The duplicated code is ~40 lines and purely mechanical.

## Risks / Trade-offs

- **Risk:** Base64 payloads for very large Android screenshots (foldable devices in unfolded mode, tablet screenshots at 2560×1600) may stress `HttpURLConnection` buffer behavior or the MCP stdio transport.
  - **Mitigation:** Initial scope is phone-sized devices (<4 MB base64). Document tablet/foldable as a known edge; address with chunked or binary endpoint only if it surfaces in practice.

- **Risk:** The pre-built test APK (`automation-server-debug-androidTest.apk`) downloaded by `install.sh` will not contain the new `ui.screenshot` method until a new release is cut. Users running an older installed APK will get a `methodNotFound` error.
  - **Mitigation:** The JSON-RPC server already returns `methodNotFound` for unknown methods. The Kotlin tool maps this to an "outdated bundle — rebuild from source or re-run install.sh for the next release" hint, identical to the iOS flow. A new tagged release refreshes the bundle.

- **Risk:** `UiAutomation.takeScreenshot()` can return `null` (e.g., in very rare cases where the display is unavailable or the security flags of the current window block capture, such as apps using `FLAG_SECURE` for DRM content).
  - **Mitigation:** The bridge checks for a `null`/empty `Bitmap` and returns a `ScreenshotResult(success = false, error = "...")` so the tool surfaces a clear message instead of a confusing base64-decode failure.

- **Risk:** Writing to an arbitrary caller-supplied path could clobber existing files.
  - **Mitigation:** Document overwrite behavior in the tool description (same as iOS). The default path uses a timestamped filename to avoid accidental overwrites when the caller doesn't pass `outputPath`.

- **Trade-off:** Code duplication between `IOSAutomationToolRegistrar.captureScreenshot` and `AndroidAutomationToolRegistrar.captureScreenshot` (~40 lines).
  - **Justification:** See Decision 8. A follow-up refactor can extract a shared helper once both callers exist and the natural seam is visible.

- **Trade-off:** Not returning the image as an MCP image content block means the agent cannot "see" the screenshot directly in the tool response — it must issue a separate read/view.
  - **Justification:** Keeps parity with `ios_screenshot` and the Kotlin → MCP string-result contract every other tool follows. Can be added later as a non-breaking enhancement on both platforms simultaneously.
