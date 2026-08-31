## Why

The August 2026 feature audit found two high-priority gaps (GitHub issues #37 and #38):

1. **No clean teardown on Android** — `ios_stop_automation_server` exists, but Android has no stop tool: after `start_automation_server`, the instrumentation process and the ADB port forward (port 9008) stay alive with no way to release them. The CLI is worse: it can start the iOS server but cannot stop it, leaving an orphaned `xcodebuild` process.
2. **No synchronization primitive** — agents must poll manually with `find_element` after every interaction, which is the main source of flaky flows and wasted agent tokens.

## What Changes

- New MCP tool `stop_automation_server` (Android): kills the running instrumentation and removes the ADB port forward. Idempotent — stopping when nothing runs succeeds with a clear message.
- New CLI subcommand `stop_automation_server` with `--platform android|ios`, wiring the existing iOS stop logic (currently MCP-only) and the new Android stop logic.
- New MCP tools `wait_for_element` / `wait_until_gone` (Android) and `ios_wait_for_element` / `ios_wait_until_gone` (iOS): accept the same selectors as `find_element` plus an optional `timeoutMs`; a single call absorbs the whole wait via client-side polling with backoff.
- New CLI subcommand `wait_for_element` with `--timeout` and `--gone`, for both platforms.
- `McpStdioE2ETest.EXPECTED_TOOLS`, the CLAUDE.md tool/CLI tables, and tool-count assertions updated accordingly (5 new MCP tools, 2 new CLI subcommands).

## Capabilities

### New Capabilities

- `automation-server-stop`: stopping a running automation server (Android instrumentation + port forward; iOS xcodebuild process) from both MCP and CLI, idempotently.
- `element-wait`: waiting for a UI element to appear or disappear within a bounded timeout, on both platforms, from both MCP and CLI.

### Modified Capabilities

<!-- none — existing specs (tool-discovery, tool-registration-dsl, install/bundle specs) are unaffected at the requirement level -->

## Impact

- `app/src/main/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrar.kt` — register `stop_automation_server`, `wait_for_element`, `wait_until_gone`.
- `app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt` — register `ios_wait_for_element`, `ios_wait_until_gone`; expose the existing stop logic for reuse by the CLI.
- `app/src/main/kotlin/com/example/visiontest/android/` (`Android.kt`, `AutomationClient.kt`) — instrumentation kill + `adb forward --remove`; polling loop reusing `ErrorHandler.retryOperation()` patterns.
- `app/src/main/kotlin/com/example/visiontest/ios/IOSAutomationClient.kt` — polling loop for iOS waits.
- `app/src/main/kotlin/com/example/visiontest/cli/` — two new subcommands (`StopAutomationServerCommand`, `WaitForElementCommand`) registered in `VisionTestCli.kt`.
- `app/src/test/` — unit tests (MockWebServer for polling, CLI parsing/exit codes) and `McpStdioE2ETest.EXPECTED_TOOLS` update.
- `CLAUDE.md` — MCP tool tables and CLI table.
- Tool timeout budgets in `ToolDsl.kt` / config: the DSL `withTimeout` for wait tools must exceed the maximum allowed `timeoutMs`.
- No changes required inside the on-device automation servers (Android/iOS JSON-RPC): waits are implemented client-side over the existing `ui.findElement` method, and stop is implemented host-side (process/adb management).
