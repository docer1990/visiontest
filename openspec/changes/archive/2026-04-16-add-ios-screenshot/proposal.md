## Why

AI agents using VisionTest can inspect the iOS simulator's UI hierarchy and interactive elements, but have no way to capture a pixel-accurate image of the current screen. A screenshot capability is essential for visual verification (layout regressions, rendering bugs, image assets) and for situations where the accessibility tree does not fully describe what the user sees (custom-drawn views, web content, media). This change adds a first-class `ios_screenshot` MCP tool that captures the simulator display and saves it as a PNG file on the host machine.

## What Changes

- Add a new MCP tool `ios_screenshot` that captures the iOS simulator's current screen and saves it as a PNG to a caller-specified (or defaulted) path on the host filesystem.
- Extend the iOS automation server (XCUITest) with a new JSON-RPC method `ui.screenshot` that returns the PNG bytes as a base64-encoded string using `XCUIScreen.main.screenshot().pngRepresentation`.
- Extend `IOSAutomationClient` with a `screenshot()` suspend function that calls the new JSON-RPC method and returns the base64 payload.
- The tool decodes the base64 payload and writes the PNG bytes to the resolved output path, returning the absolute path in the tool result.
- Scope is iOS only — Android support is deferred to a follow-up change.

## Capabilities

### New Capabilities
- `ios-screenshot`: Captures a screenshot of the booted iOS simulator display via the XCUITest automation server and saves it as a PNG file on the host.

### Modified Capabilities
<!-- None: this change introduces a new capability without altering existing spec-level behavior. -->

## Impact

- **iOS automation server (`ios-automation-server/`)** — New `screenshot()` method on `XCUITestBridge`, new `ScreenshotResult` model in `AutomationModels.swift`, new `ui.screenshot` case in `JsonRpcServer.executeMethod`. Pre-built bundle consumers (installed via `install.sh`) will need to be rebuilt/re-released for the new method to be available.
- **MCP server (`app/`)** — New `screenshot()` method on `IOSAutomationClient`, new tool registration in `IOSAutomationToolRegistrar`. No changes to shared infrastructure (`ToolFactory`, `ToolScope`, `ErrorHandler`).
- **Tests** — Unit tests for base64 decoding and file write in the Kotlin tool path; Swift unit tests for the new result model's `toDictionary()`.
- **Docs** — `CLAUDE.md` tool table needs a new row; `LEARNING.md` optionally documents the base64-over-JSON-RPC transport decision.
- **External surface** — New JSON-RPC method on the iOS automation server; no breaking changes to existing tools or endpoints.
