# CLI interactions and outcomes

## Forms, gestures, and permissions

Use targeted input to wait for a field, focus it, and type in one native request.
For an Android form submitted with the keyboard:

```bash
visiontest input_text -p android "coffee" --target-resource-id "com.example:id/search"
visiontest press_key -p android enter
visiontest wait_for_element -p android --text "Search results" --timeout 5000
```

Android targeted `input_text` sets the field value; an empty value clears it.
`clear_text` clears the focused editable Android field. `press_key` accepts
`enter`, `tab`, `backspace`, `delete`, `escape`, or a nonnegative integer Android
key code. Backspace deletes before the cursor; delete removes text after it.

For an iOS permission flow, choose the exact button label shown by the dialog:

```bash
visiontest tap_on_element -p ios --bundle-id com.example.app --text "Enable notifications"
visiontest handle_alert -p ios accept --bundle-id com.example.app --button-label "Allow"
visiontest wait_for_element -p ios --bundle-id com.example.app --text "Notifications enabled"
```

`handle_alert` searches the scoped or active app first, then system alerts in
SpringBoard. Without `--button-label`, `accept` taps the last enabled, hittable
button and `dismiss` taps the first. `dismiss_keyboard -p ios` hides a visible
software keyboard and checks that it disappeared without submitting the field.
Both commands accept optional `--bundle-id`.

`long_press` and `double_tap` accept either selectors or a complete `--x X --y Y`
pair. Prefer selectors when stable element identity matters. Use coordinates
when the screen location is intentional. Long press lasts 800 ms.

Input target flags are `--target-text`, `--target-text-contains`,
`--target-resource-id`, `--target-class-name`, and `--target-content-description`.
Selector gestures and targeted input accept `--timeout` from 1 through 30,000 ms,
defaulting to 10,000 ms. Input lookup, tap, focus, and typing share that budget.
Coordinate gestures and input without selectors reject a timeout. Optional
iOS `--bundle-id` must be nonblank and does not count as a selector; Android
rejects it.

Upgrade both Android automation APKs or the iOS XCUITest bundle before using
these new native methods or targeted input. Updating only the CLI/MCP JAR is
insufficient. Restart the automation server after upgrading its artifacts.
Inspect the action result and verify the app outcome. All 14 UI interactions
support opt-in `--json`, including `tap_on_element`, gestures and keyboard/alert
actions. JSON results contain `success` without JSON-RPC framing. A normal
`success:false` or returned RPC error still exits 0, so check the JSON fields.
Thrown failures keep the exit codes below. Without `--json`, output is unchanged.

Standalone `wait_for_element` prints `Element appeared.` or `Element gone.`.
Add `--json` for the final find result plus `condition` and `satisfied`, including
`{"found":false,"condition":"gone","satisfied":true}` for disappearance.
Timeouts and thrown failures leave stdout empty. A matching returned result with
an `error` field retains exit 0; scripts should inspect `error` as well as
`satisfied`. See [wait JSON output](cli-json.md#element-waits).

Six inspection commands support `--json`: `get_interactive_elements`,
`get_device_info`, `find_element`, `list_apps`, `info_app`, and
`available_device`. See the [JSON schemas and examples](cli-json.md),
including the action-result format.

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Command completed; inspect `found` or `success` for operation outcomes |
| 1 | Generic failure |
| 2 | Invalid or missing arguments |
| 3 | Automation server unreachable |
| 4 | Device or simulator unavailable |
| 5 | Platform unsupported by the command |


## Diagnostics

Default logs contain warnings and errors on stderr with a short logger name.
Routine INFO/DEBUG messages, thread names and timestamps are omitted. Keep
stdout and stderr separate when consuming JSON; do not use `grep -v INFO` or
hide stderr, since this can remove meaningful errors or application text.

`VISION_TEST_LOG_LEVEL` controls handled-error detail (`PRODUCTION`,
`DEVELOPMENT`, `DEBUG`), not the logger threshold. For explicit diagnostics,
SLF4J system properties can override packaged defaults, for example:

```bash
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug \
  -jar ~/.local/share/visiontest/visiontest.jar available_device -p android --json
```

Debug logging can be verbose. Use it to investigate a specific problem, then
return to the defaults for routine agent runs.
