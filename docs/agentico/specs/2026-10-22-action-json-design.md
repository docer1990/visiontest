# Structured CLI action output

Issue #49, expanded to all existing UI interactions with user approval.
Decisions: TD-003, TD-008, TD-011, TD-016. This extends their shared-operation
and output conventions without introducing a new architectural choice.

## Scope and output

Add opt-in `--json` to `tap_by_coordinates`, `tap_on_element`, `input_text`,
`long_press`, `double_tap`, `swipe_direction`, `swipe`, `swipe_on_element`,
`press_home`, Android-only `press_back`, `press_key`, `clear_text`, and iOS-only
`dismiss_keyboard`, `handle_alert`. Existing platform restrictions remain.
Waits, screenshots, hierarchy, status, lifecycle and installation are excluded.

Emit one unwrapped JSON object and a newline. Operation results require boolean
`success` and allow optional string `error`. Preserve additive result fields.
Valid returned RPC errors retain the complete error object under `error`, with
integer code and string message, using the existing shared response parser.
Returned failures retain exit 0. Malformed JSON, conflicting result/error, wrong
types and missing required fields exit 1 with stderr and empty stdout. Thrown
failures retain mapped exit codes. Never replay an action on formatting failure.

## Implementation

Use a focused `PreparedActionCommand` to own the JSON flag and standalone output
formatting, with an identity-by-default output hook on `PreparedCommand`.
Keep validation and execution in existing adapters and registrar operations.
Reuse `automationResult` and field validators. Default output stays unchanged.
Element taps request the existing raw registrar result for JSON or flow execution;
ordinary standalone taps keep their existing prose.

Flow preparation and execution keep raw responses and existing failure semantics.
Step-level output flags remain rejected before device access. MCP and native wire
methods are unchanged, so no native bundle update is required for this feature.

## Verification

Use plain TDD with existing Kotlin/JUnit CLI and mock HTTP fixtures. Parameterized
tests cover every supported action/platform pair, success, returned failure, RPC
errors, malformed results, additive fields, help, default compatibility, platform
rejection and validation before lazy component creation. Verify flow rejection of
step-level JSON and raw flow results. Existing tests cover the gateway newline;
add an executable CLI check if needed to pin the new output at that boundary.

Run `:app:test`, `:app:e2eTest`, `build` and `git diff --check`. Update CLI and
interaction contracts, JSON reference, README, CLAUDE and self-contained agent
guidance. No latency or measured token-saving claim follows from this change.
