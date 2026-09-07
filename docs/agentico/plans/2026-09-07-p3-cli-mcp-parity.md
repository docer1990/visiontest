# P3 CLI/MCP Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development to implement this plan task-by-task.

**Goal:** Deliver issue #39's CLI commands, structured inspection and iOS element swipe.

**Architecture:** Shared registrar operations follow TD-003; structured output and
native iOS lookup/gesture follow TD-008. Preserve text output and exit semantics.

**Tech Stack:** Kotlin, Clikt, Gson/kotlinx.serialization, JUnit, MockWebServer,
Swift, XCTest. No new dependencies.

**Base:** `docs/agentico-migration`, commit `46eca43`.
**Workspace:** `/private/tmp/visiontest-p3`, branch `feat/p3-cli-mcp-parity`.
**Approved specification:** `../specs/2026-09-07-p3-cli-mcp-parity-design.md`.

## Task 1: Native iOS element swipe

Files: `ios-automation-server/IOSAutomationServerUITests/Server/JsonRpcServer.swift`,
`Bridge/XCUITestBridge.swift`, `Models/AutomationModels.swift`, `Helpers/Helpers.swift`
(relative to UITests); matching `IOSAutomationServerTests/*Tests.swift`;
`app/src/main/kotlin/com/example/visiontest/ios/IOSAutomationClient.kt`,
`app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt`;
`app/src/test/kotlin/com/example/visiontest/ios/IOSAutomationClientTest.kt`.

- [x] Write tests for selector mapping, direction/speed validation, empty bounds,
  and directional endpoints inside element bounds. Wire assertion:
  `assertEquals("ui.swipeOnElement", request["method"].asString)`.
- [x] Run `./gradlew :app:test --tests '*IOSAutomationClientTest'` and applicable
  Swift tests to observe the missing behavior before implementation.
- [x] Add Kotlin client and registrar method `swipeOnElement(direction, text,
  textContains, identifier, elementType, label, bundleId, speed)` with nullable
  selectors and speed default `normal`; register `ios_swipe_on_element`.
- [x] Implement native `ui.swipeOnElement`, sharing existing element lookup.
  Require a selector, valid direction and speed. Use Android's gesture margins
  and existing iOS speed durations. Missing element or unusable bounds returns
  `OperationResult(success: false, error: ...)`.
- [x] Verify focused tests, then independent spec and quality reviews.

## Task 2: Structured inspection and six CLI commands

Files under `app/src/main/kotlin/com/example/visiontest/`:
`tools/AndroidDeviceToolRegistrar.kt`, `tools/IOSDeviceToolRegistrar.kt`,
`cli/VisionTestCli.kt`, `cli/commands/GetDeviceInfoCommand.kt`,
`cli/commands/GetInteractiveElementsCommand.kt`; new `cli/commands/FindElementCommand.kt`,
`SwipeCommand.kt`, `SwipeOnElementCommand.kt`, `ListAppsCommand.kt`,
`InfoAppCommand.kt`, `AvailableDeviceCommand.kt`; output and selector helpers in
`cli/`. Tests under `app/src/test/kotlin/com/example/visiontest/cli/` and `tools/`.

- [x] Test actual command parsing and execution using fake devices and
  MockWebServer, with an execution seam that avoids `exitProcess`.
- [x] Cover six registrations, both platforms, selector mappings, steps default
  and positive validation, required arguments, platform errors, codes 0–5 where
  applicable, and validation before backend access:
  `assertEquals(2, result.exitCode)` and `assertEquals(0, mock.requestCount)`.
- [x] Test structured output, escaping, empty apps, device metadata, raw app
  details, malformed JSON, missing/non-object results, and preserved operation
  failures. Assert that JSON stdout has no `jsonrpc` or transport `id` fields.
- [x] Run `./gradlew :app:test --tests '*Cli*' --tests '*DeviceToolRegistrarTest'`
  and observe the failures before production edits.
- [x] Implement thin command adapters with the approved argument contract.
  Expose `--json` on all six inspection commands, unwrapping UI result objects;
  malformed responses throw a generic failure. Device text and JSON share
  backend data before formatting. Preserve the default text representation.
- [x] Verify focused tests, then independent spec and quality reviews.

## Task 3: Documentation and complete verification

Files: `app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt`,
`README.md`, `CLAUDE.md`, `AGENTS.md`, `docs/agentico/specs/cli.md`, relevant
platform specs and CLI skill templates under `app/src/main/resources/`.

- [x] Update `EXPECTED_TOOLS` for `ios_swipe_on_element`; verify with
  `./gradlew :app:e2eTest`.
- [x] Document 22 CLI commands, selectors/platform mapping, JSON schemas and
  examples on both platforms, errors, and the updated iOS bundle requirement.
- [x] Run `./gradlew build`, `git diff --check`, and applicable iOS tests. Fix
  new issues without lowering coverage floors or refreshing lint baselines.
- [ ] Independently review the branch against `46eca43`; resolve blockers and
  rerun affected checks.
- [ ] Record actual verification and report branch/worktree state; leave merge
  and publication for the user's chosen integration action.

## Verification record

- Baseline: `./gradlew :app:test` passed before implementation (2026-09-07).
- Focused Kotlin P3 tests passed after implementation.
- MCP packaged-JAR contract and agent-init E2E tests passed.
- `./gradlew build` passed, including unit tests, E2E, coverage, detekt and Android lint.
- iOS unit tests passed on iPhone 17 / iOS 26.5: 77 tests, 0 failures.
