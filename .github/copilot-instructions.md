# VisionTest repository context

Read `AGENTS.md` for repository rules and the relevant behavioral contract in
`docs/agentico/specs/`. Read `kotlin-mcp-server.instruction.md` for changes under
`app/src/`. Keep public behavior and documentation consistent with those contracts.

VisionTest has a Kotlin/JVM MCP server and CLI (`app/`), an Android UIAutomator
server (`automation-server/`), and an iOS XCUITest server (`ios-automation-server/`).

## Contracts to preserve

- CLI commands and MCP tools share registrar operations. Platform clients own
  transport; registrars own validation and user-facing operation results.
- Validate CLI arguments before creating device components or contacting a
  backend. Device commands require `--platform`; Android-only commands reject
  iOS with exit code 5. Preserve documented exit codes and the distinction
  between thrown failures and normally returned operation failures.
- Structured CLI output is one JSON object without JSON-RPC framing. MCP tool
  changes require updating `McpStdioE2ETest.EXPECTED_TOOLS`.
- Native iOS JSON-RPC changes require Swift tests, Kotlin client tests, and
  compatibility guidance for installed bundles.
- Preserve coroutine cancellation, SDK thread requirements, resource ownership,
  and existing UI synchronization semantics. Do not add unconditional idle waits
  or move blocking work between dispatchers without checking the calling context.
- Retry policies must distinguish polling/read-only requests from actions with
  effects. Do not replay an action with an uncertain result merely to recover
  telemetry. Optional tracing must preserve operation results and failures;
  missing measurements are gaps, never zero durations.
- Benchmark workflows run only on manual dispatch. Keep warmups, measured
  samples, failures, and incompatible environments separate; retain evidence.
- Keep the distributable `app/src/main/resources/agent-instructions.md`
  self-contained, without repository-relative links.
- Do not lower coverage floors or regenerate detekt/lint baselines to pass checks.
  Use the verification commands in `AGENTS.md` for the affected components.
