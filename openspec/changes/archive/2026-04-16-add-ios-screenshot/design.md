## Context

VisionTest's iOS automation server runs as an XCUITest UI test process (see `AutomationServerUITest.swift`) exposing a JSON-RPC 2.0 endpoint over HTTP. The MCP server (`app/`) talks to it via `IOSAutomationClient`, which wraps `HttpURLConnection` calls to `/jsonrpc`. All JSON-RPC methods today exchange text-based JSON payloads: UI hierarchies as XML strings, element data as nested dictionaries.

Screenshots are binary PNG data — tens of KB to a few MB per image. They must cross two boundaries:
1. Simulator (XCUITest process) → Mac host (MCP server) over HTTP/JSON-RPC.
2. MCP server → caller's working directory as a PNG file.

XCUITest provides `XCUIScreen.main.screenshot()` which returns an `XCUIScreenshot` with a `.pngRepresentation: Data` property. This is the canonical simulator capture API; `simctl io screenshot` is an alternative but requires invoking the CLI and knowing the simulator UDID.

## Goals / Non-Goals

**Goals:**
- Capture the current iOS simulator display as a PNG using the existing XCUITest bridge.
- Return the file path of the saved PNG to the MCP caller, so the agent can reference it in follow-up operations.
- Allow the caller to specify an output path; default to a sensible location when omitted.
- Keep the transport mechanism consistent with existing JSON-RPC methods (no new endpoints).
- Fail clearly when the server is not running, when the output path is unwritable, or when the screenshot call itself throws.

**Non-Goals:**
- Android screenshot support (follow-up change).
- Returning the image bytes inline to the MCP caller or as an MCP image content block — initial scope is file-on-disk only.
- Capturing a specific app/window subregion — only the full simulator display.
- Image format options (JPEG, compression level, resizing) — PNG at native resolution only.
- Streaming or chunked transport for very large screenshots.

## Decisions

### Decision 1: Encode PNG as base64 over the existing JSON-RPC transport

**Choice:** The new `ui.screenshot` JSON-RPC method returns `{ "success": true, "pngBase64": "<base64 string>" }`. The Kotlin client decodes and writes to disk.

**Alternatives considered:**
- **Separate binary HTTP endpoint (`GET /screenshot`)**: Avoids base64 overhead (~33%) but doubles the surface area of the automation server, requires new routing/content-type handling in Swifter, and diverges from the single-method JSON-RPC dispatch pattern established by every other operation. Not worth it at typical screenshot sizes (a 1290×2796 iPhone 15 Pro Max screenshot is ~1–2 MB raw, ~1.3–2.7 MB base64).
- **`simctl io <udid> screenshot <path>` from the Kotlin side**: Bypasses the automation server entirely and writes directly on the host. Simpler transport, but requires knowing the booted simulator's UDID, doesn't go through the "server must be running" check, and splits the iOS automation API across two mechanisms. Rejected to keep a single coherent surface.

**Rationale:** Base64-over-JSON-RPC preserves architectural consistency, works with the existing Swifter JSON response helper, and the size overhead is acceptable for screenshots at this scale.

### Decision 2: File is written by the Kotlin MCP tool, not by the Swift side

**Choice:** The Swift bridge returns only the encoded bytes. The Kotlin tool handler decodes base64 and writes the file.

**Alternatives considered:**
- **Write the file inside the XCUITest process**: The simulator and host share the filesystem in many cases but not reliably — `XCUIScreenshot` runs in the simulator's sandbox, so paths like `~/.local/share/visiontest/screenshots/foo.png` would resolve relative to the simulator's home, not the Mac's. Cross-process path translation is error-prone.

**Rationale:** Writing on the host keeps path semantics unambiguous (caller provides a host path, host writes it).

### Decision 3: Output path parameter — optional, default is CWD-relative

**Choice:** `outputPath` is optional. When omitted, default to `./screenshots/ios_screenshot_<yyyyMMdd_HHmmss>.png` resolved against the MCP server's current working directory. When provided, the path is used as-is (both absolute and CWD-relative paths are supported).

**Alternatives considered:**
- **Default under `VISIONTEST_DIR`** (initial design): placed screenshots in the visiontest install directory (`~/.local/share/visiontest/screenshots/`). Rejected because a coding agent taking a screenshot of the iOS simulator is almost always working on a *user project*, not on visiontest itself. Screenshots belong with the project that prompted them.
- **Require outputPath explicitly (no default)**: safer but forces every caller to think about path layout. Too much ceremony for the common case.
- **A fixed absolute path like `/tmp/`**: loses the "screenshots live with the project" property and is ephemeral across reboots on some platforms.

**Rationale:** Coding agents (Claude Code, Codex, etc.) launch MCP servers with CWD set to the project they're working on. A CWD-relative default lands the PNG in the user's project directory, where the agent and user can easily reference it. The timestamped filename prevents overwrites when the agent takes multiple screenshots without specifying a path.

### Decision 4: Path safety — create parent directories, trust caller-supplied paths

**Choice:** If the caller supplies an explicit `outputPath`, trust it (the MCP server already has filesystem access under its user). Create any missing parent directories.

**Rationale:** The MCP server runs as the user; restricting caller-supplied paths would be surprising and add little defense-in-depth. The CWD-relative default lands inside the project the agent is working on.

### Decision 5: New `ScreenshotResult` model mirroring existing result types

**Choice:** Add `ScreenshotResult { success: Bool, pngBase64: String?, error: String? }` to `AutomationModels.swift` with a `toDictionary()` method following the same pattern as `UiHierarchyResult`.

**Rationale:** Consistency with the existing models; `toDictionary()` pattern is already covered by unit tests in `AutomationModelsTests.swift`.

### Decision 6: Timeout and MCP tool registration

**Choice:** Register in `IOSAutomationToolRegistrar` alongside other iOS tools. Use `timeoutMs = 30000` (same as `ios_get_ui_hierarchy`) because `XCUIScreen.main.screenshot()` typically returns in <1 second but base64 encoding + HTTP transport of a multi-MB payload warrants headroom.

**Rationale:** Matches the pattern for other potentially large-payload tools.

## Risks / Trade-offs

- **Risk:** Base64 payloads for very large screenshots (iPad 13" in 2x mode approaches 8–10 MB raw → ~13 MB base64) may stress `HttpURLConnection` default buffer behavior or the MCP stdio transport.
  - **Mitigation:** Initial scope is iPhone-sized simulators (<3 MB base64). Document the iPad limitation as a known issue; address with chunked or binary endpoint only if it surfaces in practice.

- **Risk:** The pre-built iOS automation bundle (`ios-automation-server.tar.gz`) downloaded by `install.sh` will not contain the new `ui.screenshot` method until a new release is cut. Users running an older pre-built bundle will get a `methodNotFound` error.
  - **Mitigation:** The JSON-RPC server already returns `methodNotFound` for unknown methods (see `JsonRpcServer.executeMethod` default case). The Kotlin tool should surface this cleanly with a message telling the user to upgrade or rebuild from source. A new tagged release will refresh the bundle.

- **Risk:** Writing to an arbitrary caller-supplied path could clobber existing files.
  - **Mitigation:** Document the overwrite behavior in the tool description; do not add a "confirm overwrite" flow (would break the stateless MCP tool contract). Defaults use timestamped filenames to avoid accidental overwrites.

- **Risk:** The simulator must be unlocked/booted for `XCUIScreen.main.screenshot()` to return valid data. If the simulator is in a weird state (e.g., booting, locked), the call may return a black image rather than error.
  - **Mitigation:** Accept this as a property of XCUITest; the agent can verify via `ios_get_device_info` or by inspecting the returned PNG. No extra validation in this change.

- **Trade-off:** Not returning the image as an MCP image content block means the agent cannot "see" the screenshot directly in the tool response — it must issue a separate read/view to process the PNG.
  - **Justification:** Keeps the Kotlin → MCP surface simple (string results only, like every other tool). An MCP image content response can be added later as a non-breaking enhancement if needed.
