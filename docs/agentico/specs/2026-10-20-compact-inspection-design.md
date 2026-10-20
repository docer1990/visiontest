# Compact interactive inspection

Issue #61 adds an opt-in observation format to Android and iOS, shared by CLI,
MCP and flow observations. TD-003 and TD-017 define its ownership.

## Interface

`get_interactive_elements --compact` emits one unwrapped JSON object, whether or
not `--json` is present. Existing calls remain compatible.
`--type TYPE` matches the native className exactly. `--region x,y,width,height`
matches element centers in a half-open rectangle. `--include-bounds` adds native
bounds and center coordinates. These three options require `--compact`.
Region components are signed-32-bit integers, x/y nonnegative, width/height
positive, and right/bottom must not exceed Int.MAX_VALUE. Android units are
display pixels; iOS units are screen points. No scaling or orientation conversion
is performed. Blank types and malformed regions fail before component creation.

MCP tools retain their names and add `compact`, `type`, `region` (the same string),
and `includeBounds`. Validation occurs before device checks.
`flow` accepts the same four CLI options for all requested interactive-element
observations and failure evidence. Compact options without an interactive-element
observation/evidence request fail preflight. Other observations and action results
retain their formats. Existing timeouts, failure certainty and exit codes apply.

## compact-v1

A successful object contains `success:true`, `format:"compact-v1"`, `count`
(returned elements), `observedCount` (before host filtering), and `elements`.
No result is truncated. Omitting filters retrieves all native candidates;
omitting compact retrieves full details.

Each element has `selector`, either a one-key object using existing action
argument names or null when no supported descriptive selector is available.
Selection prefers nonblank resourceId, then contentDescription, then exact text.
No selector is synthesized or composed; native lookup precedence is unchanged.
The optional `selectorMatches` field appears only for duplicate matches in the
unfiltered observation. It counts according to native semantics, including iOS
text matching label OR value. It does not assert uniqueness in the native tree
or actionability. Actions always resolve their target again.

`label` prefers nonempty text, then contentDescription. `type` preserves the
native className without role inference. A distinct contentDescription and
distinct value remain present under those names. `enabled` preserves isEnabled.
Android interaction capabilities use `clickable`, `checkable`, `scrollable`,
and `longClickable`, retaining observed booleans. Absent fields remain absent.
Repeated equivalent presentation fields and empty strings are omitted.
Unknown native attributes remain available through full inspection.
`--include-bounds` retains bounds, centerX and centerY when supplied.

Validate all source elements before filtering; malformed excluded elements must
not hide backend errors. A region filter requires centerX and centerY on every
source element, otherwise fail explicitly. No missing field is treated as zero.
Validate success/count/elements/error and native documented field types with the
existing shared validation helpers. Returned operation failures and RPC errors
retain their unwrapped failure objects; do not rewrite failures as empty success.
Standalone returned failures still exit zero; flow failure semantics are unchanged.

## Architecture and tests

Pure common options/projection helpers are called by registrar operations.
Clients and native servers remain transport/acquisition owners. CLI and MCP
only parse options; flow calls the same operations. Full-mode behavior does not
pass through the new projection.

Use BDD scenarios for compact projection, shared adapters, and flow observations,
plus focused unit tests for selector semantics, missing fields, accessibility,
duplicate targets, disabled elements, region boundaries, invalid options and
malformed/RPC failures. Registration tests verify schema and handler agreement.
No new tool name is added; existing EXPECTED_TOOLS remains valid.

## Evaluation

Compare full and compact observations from versioned synthetic Android/iOS
fixtures with labels, selectors and expected target outcomes. Report bytes and
token estimates only with an identified tokenizer. Payload reduction is not
proof of less agent reasoning or native latency.
Use the existing benchmark fixtures for form, navigation/wait and ambiguous
target flows where available. Report actual executions, failures, calls and
timing separately from offline projection tests; state unavailable agent usage.
Do not invent token measurements or silently exclude failed runs.

Update README navigation, CLI JSON/flow guides, CLI behavioral contract, MCP
reference and the self-contained distributed skill. Run :app:test, :app:e2eTest,
build and git diff --check without relaxing coverage or lint gates.
