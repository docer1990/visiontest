## Context

`startAutomationServer()` (AndroidAutomationToolRegistrar) spawns `adb shell am instrument -w …` as a detached host process and sets up `adb forward tcp:9008 tcp:9008`. Nothing ever tears either down. On iOS, `IOSAutomationToolRegistrar` keeps a `@Volatile iosXcodebuildProcess` handle and already implements stop via `process.destroyForcibly()` — but only as an MCP tool, not in the CLI.

`find_element` is a single-shot JSON-RPC call (`ui.findElement`) with no retry: agents poll it manually. The Tool DSL (`ToolDsl.kt`) wraps every tool in `withTimeout` (default 10s).

GitHub issues: #37 (stop), #38 (wait).

## Goals / Non-Goals

**Goals:**
- Idempotent stop for both platforms, from MCP and CLI.
- One-call bounded waits for element appearance/disappearance on both platforms, MCP and CLI.
- No changes to the on-device automation servers (no APK/bundle rebuild, no new JSON-RPC methods).

**Non-Goals:**
- Device selection (issue #42), other parity gaps (#39), new interactions (#40).
- Server-side waiting inside the JSON-RPC servers (see Decisions).
- Assertions (`assert_visible` etc.) — waits return data; asserting is the agent's job.

## Decisions

**1. Client-side polling for waits, not server-side.**
Polling loop lives in the Kotlin host clients (`AutomationClient` / `IOSAutomationClient`), repeatedly calling the existing `ui.findElement` with a fixed interval (500ms) until success/absence or deadline.
- *Why:* zero changes to device artifacts — no APK/test-bundle version skew, works against already-installed servers, testable with MockWebServer. A server-side wait would hold an HTTP request open for up to 30s across ADB forwarding, which is fragile.
- *Alternative rejected:* new `ui.waitForElement` JSON-RPC method — better latency (no per-poll HTTP overhead) but requires shipping new APK/bundle versions and complicates the smoke tests; revisit only if 500ms granularity proves insufficient.

**2. Android stop = `am force-stop` both packages + `adb forward --remove`.**
`stopAutomationServer()` runs `adb shell am force-stop com.example.automationserver.test`, `adb shell am force-stop com.example.automationserver`, then `adb forward --remove tcp:9008`. Each step tolerates failure (e.g. forward not present) — the tool reports what it did and always succeeds unless adb itself is unreachable.
- *Why force-stop over killing the host-side `adb` process:* the detached `ProcessBuilder` handle is not retained today, and killing the host `adb shell` does not reliably kill the on-device instrumentation. `force-stop` is what Maestro/uiautomator2 do.
- Verify by polling `isServerRunning()` going false (max ~3s); report a warning string if it still responds.

**3. Wait tools accept `find_element`'s selector set + `timeoutMs` (default 10 000, max 30 000).**
Success returns the same payload as `find_element` (for `wait_for_element`) or a confirmation string (for `wait_until_gone`). Timeout produces a structured error message naming the selectors and the elapsed time — not an exception dump.
- `wait_until_gone` treats "element not found" as success and server errors as failures (a dead server must not look like "gone").

**4. Tool DSL timeout must exceed the wait budget.**
Wait tools register with an explicit DSL timeout of `maxTimeoutMs + 5s` (35s), like the existing per-tool overrides (UI hierarchy 30s, iOS startup 200s). The CLI `--timeout` flag is validated against the same 30s cap (usage error, exit code 2, above it).

**5. CLI shape.**
- `stop_automation_server -p android|ios` — reuses the same internal functions as the MCP tools via `ComponentHolder`. Exit 0 also when nothing was running (idempotent); exit 3 only if the platform toolchain (adb) is unreachable.
- `wait_for_element -p android|ios [selector options] [--timeout MS] [--gone]` — one command, `--gone` flips to disappearance mode, mirroring how `swipe_direction` folds variants into flags. Exit 1 on timeout, 2 on missing selectors, 3 if server not running.

**6. iOS stop refactor.**
The existing stop logic in `IOSAutomationToolRegistrar` was already an internal function callable by the CLI command — no change needed.

**7. Dedicated registrar classes (settled during implementation).**
The detekt gate (baseline frozen, new findings fail) shaped where the code lives: `AndroidAutomationToolRegistrar` is at the LargeClass threshold and `IOSAutomationClient` at the TooManyFunctions threshold, so growing them was not an option. Final structure: the polling loop is one shared `pollForElement` on `JsonRpcHttpClient`; selector sets are value types (`AndroidElementSelectors` / `IOSElementSelectors`); the new tools are registered by three small registrars (`AndroidStopToolRegistrar`, `AndroidWaitToolRegistrar`, `IOSWaitToolRegistrar`), added to `ToolFactory` and exposed as derived properties on `ComponentHolder` (not constructor parameters — the constructor's shape is baselined too).

## Risks / Trade-offs

- [Force-stop kills more than the server: it terminates the automation-server app process entirely] → acceptable: that is the semantic of "stop"; document that a subsequent `start_automation_server` fully recovers.
- [Polling every 500ms adds up to 60 HTTP round-trips per 30s wait] → negligible over localhost forwarding; interval is a named constant in `AutomationConfig`/`IOSAutomationConfig` if tuning is needed.
- [`wait_until_gone` can't distinguish "gone" from "renders without the attribute searched"] → same limitation as `find_element`; documented in tool description.
- [Contract test churn] → `McpStdioE2ETest.EXPECTED_TOOLS` and CLAUDE.md tables are updated in the same commit; this is a deliberate gate, not overhead.

## Open Questions

- None blocking. Poll interval (500ms) and max timeout (30s) are starting values; revisit with real usage data.
