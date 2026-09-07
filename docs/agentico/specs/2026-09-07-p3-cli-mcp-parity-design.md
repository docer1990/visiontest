# P3: CLI/MCP parity and JSON output

Status: Approved by the user on 2026-09-07. Implements GitHub issue #39.

## Scope and command contract

Add `find_element`, `swipe`, `swipe_on_element`, `list_apps`, `info_app`, and
`available_device` to the CLI. Each requires `--platform android|ios`.

- `find_element` accepts the selector flags already used by `wait_for_element`.
  At least one selector is required; `--bundle-id` only scopes iOS searches and
  does not count as a selector.
- `swipe <startX> <startY> <endX> <endY>` accepts integer coordinates and
  `--steps` (positive integer, default 20), matching the existing MCP operation.
- `swipe_on_element <direction>` accepts the same selectors as `find_element`
  and `--speed slow|normal|fast` (default normal). Direction is up/down/left/right.
- `list_apps` and `available_device` need no operation-specific arguments.
- `info_app <id>` maps the ID to Android package name or iOS bundle ID.
- Reject an Android invocation with `--bundle-id` as a usage error.

## Shared operations

Follow TD-003: CLI and MCP delegate to shared registrar operations. Keep
platform checks, device calls, and result extraction shared. Where text-only
handlers need JSON support, extract structured data before formatting and let
both presentations use that result; never parse user-facing prose into JSON.

Add native iOS `ui.swipeOnElement`, its Kotlin client method, and the
`ios_swipe_on_element` MCP tool. Resolve selectors using existing iOS lookup
semantics and perform the directional gesture inside the selected element's
bounds. Match Android's gesture margins and speed semantics where possible.
Return an operation failure for a missing element or unusable bounds.

## JSON contract

Support `--json` on `get_interactive_elements`, `get_device_info`,
`find_element`, `list_apps`, `info_app`, and `available_device`.

- Emit exactly one JSON value followed by a newline on stdout.
- UI inspection emits the parsed operation result object, without JSON-RPC
  framing or transport IDs. Preserve platform-specific field names and types;
  document schemas and examples for each platform.
- `list_apps` emits `{"apps":["application.id"]}`; an empty list is `[]`.
- `available_device` emits an object with documented device identity and
  platform metadata, representing unavailable optional metadata as null.
- `info_app` emits an object containing the application ID and platform-specific
  information, with raw backend details in a documented string field if those
  details have no existing structured representation.
- Keep the current text presentation as the default. Preserve existing exit
  codes and stdout/stderr semantics; `--json` does not reclassify operation-level
  failures. Thrown failures go to stderr with the existing mapped exit code.
- Invalid arguments fail with code 2 before accessing a device. A malformed
  JSON backend response fails with code 1 instead of emitting invalid JSON.

## Approaches considered

1. Recommended: shared structured results and native iOS element swipe. This
   follows existing handler reuse and keeps element lookup and gesture in one
   server request, but requires Swift changes and iOS validation.
2. Compose iOS find and coordinate swipe in the Kotlin client. This avoids a
   Swift protocol addition but introduces a second request and a larger interval
   in which the target may move.
3. Wrap existing text in JSON strings. This is inexpensive but leaves scripts
   parsing prose and does not provide useful structured inspection data.

## Verification and documentation

Use plain TDD with the existing Kotlin/JUnit and Swift test infrastructure;
no new Gherkin runner or dependencies are needed for this developer-facing CLI.
Cover argument parsing, platform mapping, shared handler delegation, JSON shape
and escaping, empty results, missing elements, malformed responses, and mapped
exit codes. Test iOS gesture geometry and request validation. Update the exact
MCP tool set in `McpStdioE2ETest.EXPECTED_TOOLS`.

Update CLAUDE.md, AGENTS.md, relevant README/CLI documentation, behavioral specs,
and any generated skill templates that enumerate affected commands. Run focused
tests, the full Gradle build gate, and the applicable iOS tests; report any
device/runtime validation that could not be performed.
