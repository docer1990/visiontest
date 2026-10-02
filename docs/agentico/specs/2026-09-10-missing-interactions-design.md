# Missing interactions design

## Purpose

VisionTest must support keyboard-submitted Android forms, field clearing, long press, double tap, iOS keyboard dismissal, and iOS alert handling. Text input must also target an element without a separate tap command. Pinch and zoom remain outside this change.

## Public operations

### MCP tools

Android adds these tools:

- `android_press_key`
- `android_clear_text`
- `android_long_press`
- `android_double_tap`

iOS adds these tools:

- `ios_dismiss_keyboard`
- `ios_handle_alert`
- `ios_long_press`
- `ios_double_tap`

The existing Android `android_input_text` and iOS `ios_input_text` tools gain optional target selectors. Existing calls that provide only `text`, plus the existing optional iOS `bundleId`, retain their current behavior.

### CLI commands

The CLI adds `press_key`, `clear_text`, `long_press`, `double_tap`, `dismiss_keyboard`, and `handle_alert`. Every command requires `--platform`. `press_key` and `clear_text` support Android only. `dismiss_keyboard` and `handle_alert` support iOS only. A valid but unsupported platform exits with code 5 before the command contacts a backend.

The existing `input_text` command keeps its required positional text argument. It gains optional target selector flags. CLI and MCP commands delegate to the same registrar operations.

## Target contracts

`long_press` and `double_tap` accept exactly one target form:

1. Both integer coordinates `x` and `y`.
2. At least one element selector.

A request fails validation if it provides neither form, only one coordinate, or both coordinates and selectors. Element selectors use the existing platform mapping for exact text, partial text, resource identifier, class name, and content description. On iOS these map to text, partial text, accessibility identifier, element type, and accessibility label. Optional iOS `bundleId` scopes the application and does not count as a selector.

Targeted `input_text` uses `targetText`, `targetTextContains`, `targetResourceId`, `targetClassName`, and `targetContentDescription` in MCP and JSON-RPC requests. The CLI equivalents start with `--target-`. The prefix avoids a conflict with the existing `text` value to type. Callers may provide no target selectors or at least one target selector. A request that supplies `timeoutMs` without a target selector fails validation. Partial coordinate input does not apply to text input.

Selector-based gestures and targeted input accept an optional `timeoutMs`. The default is 10,000 ms, and valid values range from 1 through 30,000 ms. Coordinate gestures reject `timeoutMs`. iOS app scope accepts a nonblank `bundleId`; Android rejects app scope.

## Native JSON-RPC methods

The Android automation server adds:

- `ui.pressKey`
- `ui.clearText`
- `ui.longPress`
- `ui.doubleTap`

The iOS automation server adds:

- `ui.dismissKeyboard`
- `ui.handleAlert`
- `ui.longPress`
- `ui.doubleTap`

Both servers extend `ui.inputText` with the optional `target*` selectors and `timeoutMs`. Existing request shapes remain valid.

The native server performs selector lookup, readiness polling, and the interaction in one JSON-RPC request. This preserves the atomic interaction boundary established by TD-010. Coordinate gestures execute immediately.

## Readiness and polling

Native servers poll selector targets every 500 ms until the timeout. Android gesture targets must be visible and enabled. iOS gesture targets must exist, be enabled, and be hittable. The server retains the native element reference that passed readiness checks and performs the gesture on that reference.

Targeted text input waits for an editable, visible, enabled element. iOS also requires the element to be hittable. The server taps the element, waits for it to acquire keyboard focus within the original timeout budget, and types the requested text. Lookup, tap, focus confirmation, and input form one atomic request. The timeout does not restart after the tap.

iOS polling services the runner's default-mode run loop between attempts. Other main-queue automation commands remain serialized behind the active request.

Failures distinguish an absent-element timeout, an element that remained blocked or noneditable, failure to acquire focus, and a native interaction error. A failed operation must not claim that the gesture or text input succeeded.

## Android keyboard operations

`android_press_key` accepts exactly one of a nonnegative Android key code or a named action. The CLI accepts one positional value and treats an unsigned decimal value as a key code. Named actions are `enter`, `tab`, `backspace`, `delete`, and `escape`. They map to stable Android key constants. `backspace` deletes before the cursor, while `delete` deletes after it.

`android_clear_text` operates on the focused editable element. It fails normally if no focused editable element exists. It does not accept selectors in this change. A caller that needs to target a field first may use `input_text` with an empty text value and target selectors to focus it, then call `android_clear_text`.

## Gesture behavior

Long press has a fixed duration of 800 ms on both platforms. Double tap uses the platform's native double-tap interaction where available. Android uses two taps separated by 100 ms when it must compose the gesture.

Selector-based gestures act on the native element rather than converting bounds to host-side coordinates. Coordinate gestures use the supplied screen point. Invalid coordinates and native gesture failures return an operation failure without a success message.

## iOS keyboard dismissal

`ios_dismiss_keyboard` accepts an optional `bundleId`. It locates a visible software keyboard in the scoped or active application, performs a downward keyboard dismissal gesture, and verifies that the keyboard disappears. It returns a normal operation failure if no keyboard is visible or the keyboard remains visible after the gesture.

The operation does not submit the field, tap an arbitrary application coordinate, or choose a keyboard button whose meaning may vary by layout.

## iOS alert handling

`ios_handle_alert` requires `action` with `accept` or `dismiss`. It accepts optional `buttonLabel` and `bundleId`. The native server searches the scoped or active application first, then the system SpringBoard process so permission dialogs are reachable.

When `buttonLabel` is present, the server taps an enabled, hittable button with that exact label. Without a label, `accept` selects the last enabled and hittable alert button, while `dismiss` selects the first. This matches common iOS alert ordering and keeps the fallback deterministic. The result identifies the tapped button. Missing alerts, missing requested buttons, and blocked buttons return distinct operation failures.

## Validation and error handling

CLI commands validate platform support, selector combinations, coordinates, actions, key values, bundle scope, and timeout ranges before they create device components or contact a backend. Registrar operations apply the same validation for MCP callers.

Transport failures and method-not-found responses retain existing behavior. Operation-level failures remain normal returned results and exit with code 0 in text-mode CLI commands. Installed Android APKs and iOS bundles must be updated before the new native methods or targeted input shape can be used.

## Documentation

Implementation updates the command contract in `docs/agentico/specs/cli.md`, the command and tool tables in `CLAUDE.md`, and the self-contained guidance in `app/src/main/resources/agent-instructions.md`. `McpStdioE2ETest.EXPECTED_TOOLS` must contain every new MCP tool.

## Testing approach

The implementation uses plain test-driven development rather than Gherkin scenarios. Each behavior starts with a failing unit, integration, or native test.

Kotlin client tests cover method names and JSON parameters. Registrar tests cover validation, readiness options, and returned results. CLI tests cover command registration, argument adaptation, pre-backend validation, platform rejection, output, and exit codes. MCP end-to-end tests cover the packaged tool list and schemas.

Android tests cover JSON-RPC dispatch, named and numeric keys, focused-field clearing, target validation, readiness polling, focus acquisition, and gesture success and failure paths. Swift tests cover dispatch, application and SpringBoard alert lookup, deterministic button selection, keyboard dismissal verification, target readiness, run-loop polling, focus acquisition, and native gestures.

The final verification runs `./gradlew :app:test`, `./gradlew :app:e2eTest`, `./gradlew build`, the native iOS test command from `AGENTS.md`, and `git diff --check`.
