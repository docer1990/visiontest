## 1. Android stop_automation_server

- [x] 1.1 Add `stopAutomationServer()`: `am force-stop` on test + app packages, `adb forward --remove tcp:9008` (tolerating "not found"), then poll `isServerRunning()` up to ~3s to verify shutdown; idempotent success message when nothing was running (landed in dedicated `AndroidStopToolRegistrar` — detekt LargeClass forbids growing `AndroidAutomationToolRegistrar`)
- [x] 1.2 Register the `stop_automation_server` MCP tool (description + input schema)
- [x] 1.3 Unit tests: stop while running (recording DeviceConfig fake + MockWebServer health), stop while not running (idempotent), adb unreachable (structured error) — `AndroidStopToolRegistrarTest`

## 2. iOS stop refactor

- [x] 2.1 iOS stop logic already extracted as `internal stopAutomationServer()` in `IOSAutomationToolRegistrar` — verified reusable by the CLI, no change needed

## 3. Wait polling in clients

- [x] 3.1 Add poll-interval and wait-timeout constants (500ms interval; 10s default / 30s max; 35s DSL budget) to `AutomationConfig` and `IOSAutomationConfig`
- [x] 3.2 Shared polling loop `pollForElement` in `JsonRpcHttpClient` (both platforms), distinguishing "not found" from server errors (JSON-RPC error member / dead server fail the wait, never report "gone") — kept out of the platform clients to respect detekt TooManyFunctions on `IOSAutomationClient`
- [x] 3.3 Selector value types `AndroidElementSelectors` / `IOSElementSelectors` with `hasAnySelector()` + `describe()`
- [x] 3.4 Unit tests with MockWebServer: appears after N polls, never appears (timeout message includes selectors + elapsed), gone after N polls, server dies mid-wait fails the gone-wait, malformed/JSON-RPC-error responses — `AutomationClientWaitTest`

## 4. Wait MCP tools

- [x] 4.1 `wait_for_element` / `wait_until_gone` registered by new `AndroidWaitToolRegistrar` with `find_element`'s selector schema + `timeoutMs`; selector presence and 30s cap validated; DSL timeout 35s; `requireServer()` first
- [x] 4.2 `ios_wait_for_element` / `ios_wait_until_gone` registered by new `IOSWaitToolRegistrar` (adds `bundleId`)
- [x] 4.3 Unit tests for parameter validation (no selector, timeout over cap, server not running) — `AndroidWaitToolRegistrarTest`, `IOSWaitToolRegistrarTest`

## 5. CLI subcommands

- [x] 5.1 `StopAutomationServerCommand` (`--platform android|ios`): exit 0 when stopped or already stopped; registered in `VisionTestCli`
- [x] 5.2 `WaitForElementCommand` (`--platform`, selector options, `--timeout` capped at 30000, `--gone`): exit 0 found/gone, 1 timeout, 2 usage, 3 server not running; registered in `VisionTestCli`
- [x] 5.3 CLI unit tests: exit codes 0/1/2/3 for wait, idempotent stop exit 0 — `CliCommandIntegrationTest`, subcommand set in `VisionTestCliTest`

## 6. Contract + docs

- [x] 6.1 Added the 5 new tools to `McpStdioE2ETest.EXPECTED_TOOLS`
- [x] 6.2 Updated CLAUDE.md: Android/iOS tool tables, CLI command table, timeout notes in Key Patterns
- [x] 6.3 `./gradlew build` (tests + e2e + koverVerify + detekt + lint) green; coverage floor untouched

## 7. Wrap-up

- [ ] 7.1 Reference GitHub issues #37/#38 in the PR description and check acceptance criteria against the spec scenarios
