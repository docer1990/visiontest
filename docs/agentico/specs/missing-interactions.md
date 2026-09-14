# Missing interactions

## Purpose

VisionTest supports Android keyboard submission and focused-field clearing,
long press and double tap on both platforms, iOS keyboard dismissal and alert
handling, and text input that selects and focuses a field in one request.
Pinch and zoom are outside this contract.

## Public operations

| CLI command | Android MCP tool | iOS MCP tool | Native JSON-RPC method |
| --- | --- | --- | --- |
| `press_key` | `android_press_key` | Unsupported | `ui.pressKey` |
| `clear_text` | `android_clear_text` | Unsupported | `ui.clearText` |
| `long_press` | `android_long_press` | `ios_long_press` | `ui.longPress` |
| `double_tap` | `android_double_tap` | `ios_double_tap` | `ui.doubleTap` |
| `dismiss_keyboard` | Unsupported | `ios_dismiss_keyboard` | `ui.dismissKeyboard` |
| `handle_alert` | Unsupported | `ios_handle_alert` | `ui.handleAlert` |
| `input_text` | `android_input_text` | `ios_input_text` | `ui.inputText`, extended with optional targets |

Every device CLI command MUST require an explicit platform. A valid unsupported
platform MUST exit 5 before device setup or backend access. CLI and MCP callers
MUST use the same registrar operations. The [CLI contract](cli.md) defines
argument errors, output channels, and exit codes.

## Target validation

`long_press` and `double_tap` MUST accept exactly one target form: both
nonnegative integer coordinates `x` and `y`, or at least one nonblank selector.
They MUST reject absent targets, partial coordinate pairs, and mixed coordinates
and selectors. CLI coordinates use `--x` and `--y`.

| CLI selector | MCP and JSON-RPC field | Android match | iOS match |
| --- | --- | --- | --- |
| `--text` | `text` | Exact text | Exact text |
| `--text-contains` | `textContains` | Partial text | Partial text |
| `--resource-id` | `resourceId` | Resource identifier | Accessibility identifier |
| `--class-name` | `className` | Class name | Element type |
| `--content-description` | `contentDescription` | Content description | Accessibility label |

Targeted `input_text` MUST keep the required text value separate from the target.
It MUST use `targetText`, `targetTextContains`, `targetResourceId`,
`targetClassName`, and `targetContentDescription` in MCP and JSON-RPC requests.
The CLI equivalents are `--target-text`, `--target-text-contains`,
`--target-resource-id`, `--target-class-name`, and `--target-content-description`.
Their platform meanings match the table above.

Input MUST accept either no target selectors or at least one nonblank target
selector. Calls with only `text`, including an empty string, MUST retain the
existing focused-input behavior. The existing optional iOS `bundleId` MUST
remain supported. Input does not accept coordinate targets.

Selector gestures and targeted input MUST accept `timeoutMs`, exposed as
`--timeout MS` in the CLI. Its default MUST be 10,000 ms and its inclusive range
MUST be 1 through 30,000 ms. Coordinate gestures and input without target
selectors MUST reject a supplied timeout.

iOS `bundleId`, exposed as `--bundle-id`, MUST be nonblank when supplied and MUST
scope the application. It MUST NOT count as a selector. Android MUST reject
app scope. The CLI MUST validate target combinations, keys, actions, scope, and
timeouts before creating device components or contacting a backend. Registrars
MUST apply the same validation for MCP requests.

## Readiness and one timeout budget

Native servers MUST perform target lookup, readiness polling, and interaction
within one JSON-RPC request. They MUST poll selector targets every 500 ms until
the timeout. Android gesture targets MUST be visible and enabled. iOS gesture
targets MUST exist, be enabled, and be hittable. The gesture MUST act on the
native element reference that passed readiness checks, without host-side
conversion to coordinates. Coordinate gestures MUST execute immediately.

Targeted input MUST wait for an editable, visible, enabled element. iOS MUST
also require it to be hittable. The server MUST tap the element, confirm
keyboard focus, and type the requested text within the original timeout budget.
The timeout MUST NOT restart after lookup or tap. Lookup, readiness, tap, focus
confirmation, and input MUST remain one atomic native request.

iOS polling MUST service the runner's default-mode run loop between attempts.
Other main-queue automation commands MUST remain serialized behind the active
request.

Failures MUST distinguish a target that never appeared, a target that remained
blocked or noneditable, failure to acquire focus, and a native interaction
error. Failed operations MUST NOT report that the gesture or input succeeded.

## Android keyboard operations

`android_press_key` MUST accept exactly one nonnegative integer `keyCode` or
named `action`. The CLI MUST accept one positional value and interpret unsigned
decimal input as a key code. Numeric codes MUST fit a signed 32-bit integer.

| Action | Android key constant | Behavior |
| --- | --- | --- |
| `enter` | `KEYCODE_ENTER` | Press Enter, including keyboard-submitted forms |
| `tab` | `KEYCODE_TAB` | Press Tab |
| `backspace` | `KEYCODE_DEL` | Delete before the cursor |
| `delete` | `KEYCODE_FORWARD_DEL` | Delete after the cursor |
| `escape` | `KEYCODE_ESCAPE` | Press Escape |

`android_clear_text` MUST clear the focused editable element. It MUST return a
normal operation failure when no focused editable element exists. It MUST NOT
accept selectors. To focus a field before clearing it, callers MAY use targeted
`input_text` with an empty text value, then call `clear_text`.

## Gestures

Long press MUST last 800 ms on both platforms. Double tap MUST use the native
double-tap interaction where available. Android MUST use two taps separated by
100 ms when it composes the gesture. Selector gestures MUST act on the selected
native element; coordinate gestures MUST use the supplied screen point.
Invalid screen positions and native gesture failures MUST return operation
failures without success messages.

## iOS keyboard dismissal

`ios_dismiss_keyboard` MUST accept optional `bundleId`. It MUST locate a visible
software keyboard in the scoped or active app, perform a downward dismissal
gesture on the keyboard, and verify its disappearance. No visible keyboard and
a keyboard that remains visible MUST return normal operation failures.

Dismissal MUST NOT submit the field, tap an arbitrary app coordinate, or choose
a keyboard button whose meaning depends on the layout.

## iOS alerts

`ios_handle_alert` MUST require `action` with `accept` or `dismiss` and MUST
accept optional `buttonLabel` and `bundleId`. The native server MUST search the
scoped or active app first, then SpringBoard so it can reach system permission
dialogs.

When a caller supplies `buttonLabel`, the server MUST choose an enabled,
hittable alert button with that exact label. Without a label, `accept` MUST
choose the last enabled, hittable button and `dismiss` MUST choose the first.
This fallback MUST preserve the native alert button order. The successful
result MUST identify the button tapped. Missing alerts, missing requested
buttons, and blocked buttons MUST return distinct operation failures.

## Results and native compatibility

Operation failures MUST remain normal returned results. Text-mode CLI commands
MUST print those results to stdout and exit 0, even when the result reports a
failure. Thrown failures, transport failures, and method-not-found responses
MUST retain their existing handling. These interactions MUST NOT introduce a
new structured CLI output format or alter the documented exit codes.

Callers MUST upgrade both Android automation APKs or the iOS XCUITest bundle
before using the new native methods or targeted `ui.inputText` fields. Updating
only the CLI/MCP JAR is insufficient. Older native servers may lack methods or
ignore new target fields, so callers MUST NOT assume that a successful legacy
input response proves the requested field received text. Existing untargeted
input request shapes MUST remain valid with the updated servers.

Agents SHOULD prefer selectors when stable element identity matters. They
SHOULD use coordinates when the screen location itself is intentional, and
inspect the UI again after navigation or layout changes before reusing them.
After any interaction, callers SHOULD verify an observable app outcome rather
than treating an operation result or exit code as proof of the whole flow.
