# iOS App Scope and Element Tap Design

## Context

Issue #50 identifies three CLI adapters that cannot forward the iOS application scope already supported by their MCP, registrar, client, and native-server paths: `get_ui_hierarchy`, `get_interactive_elements`, and `input_text`. With no bundle ID, the iOS bridge intentionally queries Springboard, so these CLI commands can inspect or interact with the wrong application after a launch.

The existing `find_element` and `swipe_on_element` commands accept `--bundle-id`, but expose it under the `Element selectors` help group even though it selects an application and does not identify an element.

Scripts and MCP callers also need a direct element tap that absorbs the delay between loading a screen and an element becoming actionable. Requiring callers to sleep, poll, extract bounds, and tap coordinates is repetitive and creates a race when the UI moves.

## Goals

- Expose consistent optional iOS app scope in the three affected CLI commands.
- Separate application scope from element selectors in parsing, validation, and help.
- Add CLI and MCP element-tap operations for Android and iOS.
- Make element tap wait for actionability before tapping the same native element reference.
- Preserve shared registrar operations between CLI and MCP.
- Preserve omission of the iOS bundle ID as an explicit Springboard target.

## Non-goals

- Do not change the selector matching rules or precedence of either platform.
- Do not add automatic scrolling to reveal off-screen elements.
- Do not change `wait_for_element` or `wait_until_gone`; TD-006 continues to govern passive waits.
- Do not make coordinate taps application-scoped.
- Do not add structured JSON output to commands not already covered by the CLI JSON contract.

## Application Scope Design

Introduce an `IosAppScopeOptions` Clikt option group containing only:

```text
--bundle-id TEXT  Target app bundle ID on iOS; omit only for Springboard
```

The group title is `iOS app scope`. It validates that a supplied value is nonblank and that the selected platform is iOS. Validation runs before `components` is evaluated, before a health check, and before any backend request.

`get_ui_hierarchy`, `get_interactive_elements`, and `input_text` use the group and forward its value unchanged to the existing iOS registrar operations. They ignore no supplied value: Android plus `--bundle-id` is a usage error with CLI exit code 2. Omitting the option continues to pass `null`, preserving Springboard behavior.

`ElementSelectorOptions` retains only `--text`, `--text-contains`, `--resource-id`, `--class-name`, and `--content-description`. `find_element`, `swipe_on_element`, and the new `tap_on_element` compose it with `IosAppScopeOptions`. A bundle ID alone never satisfies selector validation.

## Element Tap Interfaces

The unified CLI command is:

```text
tap_on_element --platform <android|ios> [selectors] [--bundle-id ID] [--timeout MS]
```

It requires at least one element selector. `--bundle-id` follows the application-scope rules above. `--timeout` is optional, defaults to 10,000 ms, must be between 1 and 30,000 ms, and is validated before backend access.

MCP exposes `tap_on_element` on Android and `ios_tap_on_element` on iOS. Both accept the existing platform selector fields plus optional `timeoutMs`; the iOS tool also accepts optional `bundleId`. The MCP tool timeout must exceed the maximum operation timeout so the operation produces its own diagnostic first.

The new CLI and MCP adapters delegate to the same internal suspend operation on their platform registrar, following TD-003. The MCP tool set grows by two and `McpStdioE2ETest.EXPECTED_TOOLS` must change accordingly.

## Native Wait-and-Tap Operation

Following TD-010, both platform clients send one `ui.tapOnElement` JSON-RPC request containing selectors and the resolved timeout. The native automation server retains control from the first lookup through the gesture:

```text
validate request
  -> look up element
  -> if actionable, tap that element and return success
  -> otherwise wait up to the next 500 ms interval and retry
  -> at the deadline, fail without tapping
```

Android considers an element actionable when the selector resolves to an enabled `UiObject2` whose visible bounds have positive area and intersect the display. It invokes `UiObject2.click()` rather than `UiDevice.click(x, y)`.

iOS considers an element actionable when the selector resolves to an `XCUIElement` for which `exists`, `isEnabled`, and `isHittable` are true. It invokes `XCUIElement.tap()`.

The native operation tracks whether it ever observed a matching but non-actionable element. A timeout reports either `Element not found` or `Element found but not tappable`, together with the timeout and selector description. Timeout is a failed command, not a successful operation result: MCP returns an error and CLI writes the message to stderr with exit code 1. A successful tap returns the normal platform operation success response.

The host HTTP read timeout for this method must exceed the requested native wait by a small fixed grace period. This prevents the transport timeout from hiding the operation's own deadline diagnostic at the maximum supported timeout.

## Error Handling

- Missing selectors, blank selector values, invalid timeout, Android app scope, and blank bundle IDs fail before component creation or backend access.
- An unreachable automation server keeps the existing mapped MCP error and CLI exit code 3.
- A native timeout fails without tapping and is surfaced as described above.
- Native transport, JSON-RPC, lookup, or gesture failures remain errors and are never reported as absence or successful taps.
- Omitting iOS app scope intentionally targets Springboard for inspection, input, selection, and tap operations.

## Compatibility

`ui.tapOnElement` is a new native JSON-RPC method. Android users need the updated automation-server APKs. iOS users need a rebuilt or updated `.xctestrun` bundle; an older installed bundle returns `Method not found`. Release and usage guidance must state this requirement.

The issue #50 app-scope changes reuse existing JSON-RPC parameters and remain compatible with current native servers independently of `tap_on_element`.

## Testing Strategy

Use plain Kotlin, Android, and Swift TDD rather than adding a Gherkin framework.

Host-side tests cover:

- forwarding and omission of `--bundle-id` for hierarchy, interactive elements, and input;
- Android and blank bundle-ID rejection before component or backend access;
- the dedicated help group and selector/app-scope separation;
- bundle-only selector command rejection;
- CLI registration, selector mapping, timeout validation, and error mapping for `tap_on_element`;
- MCP schemas, handlers, expected tool names, and platform client payloads;
- method-specific HTTP timeout behavior.

Native Android and Swift tests cover:

- request parsing and selector validation;
- immediate tap of an actionable element;
- retry until an element becomes actionable;
- distinct absent and non-actionable timeout failures;
- no tap on timeout;
- direct native element tapping and iOS bundle scoping.

The final gate is `./gradlew :app:test`, `./gradlew :app:e2eTest`, `./gradlew build`, `git diff --check`, and the configured iOS unit-test command.

## Documentation

Update `docs/agentico/specs/cli.md`, `README.md`, `CLAUDE.md`, and the self-contained `app/src/main/resources/agent-instructions.md` with app-scoping rules, command syntax, implicit wait behavior, timeout limits, and native artifact compatibility. Native iOS JSON-RPC documentation must make the bundle-version requirement explicit.
