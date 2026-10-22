# Symmetric CLI Wait Output Implementation Plan

> **For agentic workers:** Use agentico:subagent-driven-development.

**Goal:** Implement issue #53 with symmetric text and JSON wait output.

**Architecture:** Adapt existing registrar waitResult responses at the standalone
CLI boundary. Use the existing find-result validator and flow condition schema.

**Tech Stack:** Kotlin, Clikt, kotlinx.serialization, JUnit, MockWebServer.

**Branch:** feat/issue-53-wait-output off main

**Decisions:** TD-003, TD-006

## File structure

- `app/src/main/kotlin/com/example/visiontest/cli/commands/WaitForElementCommand.kt`
  selects the shared raw operation and formats standalone output.
- `app/src/main/kotlin/com/example/visiontest/cli/WaitOutput.kt`, if needed,
  validates and formats the standalone result.
- `app/src/test/kotlin/com/example/visiontest/cli/WaitOutputTest.kt` and
  `WaitOutputE2ETest.kt` cover output and gateway behavior.
- `docs/agentico/specs/cli.md`, `element-waits.md`, `docs/cli-json.md`,
  `README.md`, `CLAUDE.md`, and `app/src/main/resources/agent-instructions.md`
  document the public behavior.

### Task 1: Implement, test and document standalone wait output

**Review:** checkpoint (public contract and final task).

Write failing tests using the actual command parser and FlowObservationFixture.
For both platforms and both modes assert the successful text and JSON outputs:

```kotlin
assertEquals(CliResult(0, "Element appeared.", null), textResult)
assertEquals(CliResult(0, "{\"found\":false,\"condition\":\"gone\",\"satisfied\":true}", null), goneResult)
```

Cover additive fields, selector mapping, timeout exit 1, invalid input exit 2
before component creation, stopped server exit 3, RPC/protocol errors, malformed
matching results, and existing operation-failure semantics. Verify no partial
stdout or repeated operation. Keep flow and MCP regressions green; reject
step-level JSON before backend access. Add a packaged CLI test for one object
with a newline, using owned mock servers and skipping occupied ports without
contacting existing native services.

Add --json and formatStandaloneOutput to WaitForElementCommand. Both platform
paths call registrar.waitResult with expectGone. Validate/unwrap through existing
helpers and enrich with condition/satisfied as flow already does. Default output
is concise symmetric text. Keep raw flow execution, MCP, native code and polling
unchanged. Preserve thrown and returned failure semantics.

Run `./gradlew :app:test --tests '*Wait*' --tests '*FlowPreparationTest'
--tests '*FlowPreparedExecutionTest' --quiet` for red/green evidence.
Update all documentation in the file map and remove stale wait output claims.
Run `./gradlew :app:e2eTest --tests '*WaitOutputE2ETest' --quiet`.
Commit as `feat(cli): make wait output symmetric and machine-readable`.
Final verification runs `./gradlew :app:test :app:e2eTest build --quiet` and
`git diff --check`, followed by independent review.
