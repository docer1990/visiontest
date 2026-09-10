# Structured CLI validation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Reject inspection JSON that violates the documented command schema before printing stdout.

**Architecture:** Keep RPC handling and schema validation in the shared CLI inspection formatter. Command adapters supply command identity and platform. Preserve valid RPC errors per TD-011.

**Tech Stack:** Kotlin, kotlinx.serialization JSON, existing Kotlin tests and MockWebServer.

## Task 1: Validate inspection results

Files are relative to app/src/.
Modify main/kotlin/com/example/visiontest/cli/InspectionOutput.kt and the GetDeviceInfoCommand.kt, FindElementCommand.kt, and GetInteractiveElementsCommand.kt adapters in its commands directory. Add a focused schema validator file if necessary. Extend test/kotlin/com/example/visiontest/cli/ParityCliTest.kt or add focused tests in the same package.

- [x] Write failing regression tests using the existing invoke/respond helpers. For example, respond(androidServer, "{}"), invoke GetDeviceInfoCommand with -p android --json, and assert exitCode equals 1, stdout is null, and stderr is non-null. Repeat for all three commands and both platforms. Exercise each missing required field and wrong type, nested non-object elements, optional fields absent/present/invalid, extra fields, and valid/invalid RPC errors.
- [x] Run ./gradlew :app:test --tests com.example.visiontest.cli.ParityCliTest and confirm failures represent accepted malformed payloads.
- [x] Extend the internal inspectionOutput(response: String, json: Boolean, command: InspectionCommand, platform: Platform): String interface. Define InspectionCommand entries for DEVICE_INFO, FIND_ELEMENT, INTERACTIVE_ELEMENTS. Validate docs/cli-json.md required and optional fields without coercing strings to numbers or booleans. Preserve unknown fields. Keep RPC handling shared and text output unchanged. Update older test fixtures to satisfy the documented schema.
- [x] Run focused tests and commit the implementation and tests.

## Task 2: Document and verify

- [x] Update docs/cli-json.md and docs/agentico/specs/cli.md with required-field validation, optional-field types, additive compatibility, and valid RPC error shape/exit rule. Include malformed response and valid RPC error scenarios.
- [x] Run ./gradlew :app:test, ./gradlew :app:e2eTest, ./gradlew build, and git diff --check. Resolve new failures without lowering quality gates.
- [x] Review the complete diff against issue #54, then commit the documentation and leave the branch ready for review.

## Verification results

The initial incomplete-result regression failed with expected exit 1 and actual exit 0. The final gate `./gradlew :app:test :app:e2eTest build` passed, with 386 app unit tests and 5 end-to-end tests. Detekt, coverage verification, Android builds and lint passed. `git diff --check` passed. Independent review found no blockers; its wording correction about CLI integer limits was applied.
