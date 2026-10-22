# Structured CLI Action Output Implementation Plan

> **For agentic workers:** Use agentico:subagent-driven-development to implement this plan task-by-task.

**Goal:** Add consistent opt-in JSON to all 14 existing UI interaction commands.

**Architecture:** Keep registrar operations and transport unchanged. A prepared
action adapter formats only standalone JSON output through the existing response
parser; flow continues to receive raw results.

**Tech Stack:** Kotlin, Clikt, kotlinx.serialization, JUnit, MockWebServer.

**Branch:** feat/issue-49-action-json off main

**Decisions:** TD-003, TD-008, TD-011, TD-016

## File structure

- `app/src/main/kotlin/com/example/visiontest/cli/PreparedCommand.kt`: identity
  standalone output hook, leaving flow unchanged.
- `app/src/main/kotlin/com/example/visiontest/cli/PreparedActionCommand.kt`: shared
  action JSON flag and strict result formatting.
- `app/src/main/kotlin/com/example/visiontest/cli/commands/`: the 14 action adapters
  listed in the spec adopt the shared base. Element tap selects its raw result.
- `app/src/test/kotlin/com/example/visiontest/cli/ActionJsonOutputTest.kt`: the
  action/platform output and validation matrix using `FlowObservationFixture`.
- Existing CLI flow/preparation and E2E tests: compatibility and gateway output.
- `docs/agentico/specs/cli.md`, `element-tap.md`, `missing-interactions.md`,
  `docs/cli-json.md`, `README.md`, `CLAUDE.md`,
  `app/src/main/resources/agent-instructions.md`: public contract and agent usage.

### Task 1: Implement and verify action JSON

**Review:** checkpoint (public output contract).

Write failing CLI tests first. Use each action's actual Clikt parser and injected
runner with `FlowObservationFixture(platform)` and `executeCliCommand`. Cover all
23 supported command/platform pairs and assert, for example:

```kotlin
assertEquals(0, result.exitCode)
assertEquals("{\"success\":true}", result.stdout)
assertNull(result.stderr)
```

Exercise success with preserved extra fields, success:false with optional error,
valid RPC error with data, missing/wrong success, invalid optional error, malformed
JSON, non-object results and conflicting RPC error/result. Cover default output
including tap prose, JSON help, unsupported platforms, invalid arguments before
component creation, thrown failures with empty stdout, and flow rejection of
step-level JSON. Pin one-object newline through the real gateway where feasible.

Add `protected open fun formatStandaloneOutput(response: String): String` with
identity default to PreparedCommand, applied inside the runner after execution
only. Add PreparedActionCommand with protected boolean `jsonOutput`, owning
`--json`, and override formatting through automationResult and strict field
validators. Adopt it in the 14 adapters. TapOnElementCommand captures
`preparingForFlow || jsonOutput` to select the existing raw registrar operation.
Do not broaden parser semantics, touch native code, add retries or alter flow.

Run `./gradlew :app:test --tests '*ActionJson*' --tests '*FlowPreparationTest'
--tests '*FlowPreparedExecutionTest' --tests '*ParityCliTest' --quiet`; expect
all selected tests to pass after the demonstrated red run. Commit the change.

User constraint added during execution: do not use the active iPhone 17 / iOS 27
simulator. Isolate the existing `FlowCommandE2ETest` failure-report subprocess
case with an owned mock server before full E2E execution. Bind before launching
the CLI and skip occupied platform ports without querying existing services.
Keep Android and iOS cases independent and preserve the failure-report assertions.
Verify with `:app:e2eTest --tests '*FlowCommandE2ETest' --tests '*ActionJsonE2ETest'`.
This test-only safety adjustment is reviewed with Task 1 before documentation.

### Task 2: Document the expanded action contract

**Review:** checkpoint (final public documentation).

Update the documentation files in the map. Remove assertions that element taps
and newer interactions never offer JSON. List all 14 commands and their platform
restrictions, success/error schemas, optional fields and additive-field retention,
normal-return exit 0 and thrown-error mapping, default compatibility and unchanged
flow behavior. Explain that agents check RPC error objects and success rather than
exit code alone. Keep distributed instructions self-contained. Give concise CLI
examples using `--json` without claiming native speedups or measured token savings.

Search for stale no-JSON statements and verify docs match tests. Run the full
gate `./gradlew :app:test`, `./gradlew :app:e2eTest`, `./gradlew build`, then
`git diff --check`; expect exit 0. Commit documentation. Run final branch review.
