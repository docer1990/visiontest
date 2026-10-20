# Compact inspection implementation plan

> Use agentico:subagent-driven-development with sequential implementers and independent checkpoint reviews.

**Goal:** Deliver issue #61 across CLI, MCP and flow without native upgrades.

**Architecture:** Registrar operations apply a pure common projection to fresh
native observations. Adapters parse identical options; full-mode paths remain
compatible. See the approved compact-inspection design dated 2026-10-20.

**Tech Stack:** Kotlin, kotlinx.serialization, Clikt, JUnit dynamic BDD tests.

**Branch:** feat/issue-61-compact-inspection off origin/main (f956cb1).

**Decisions:** TD-003, TD-017.

## File structure

- `common/CompactInspection.kt` owns options, validation and projection, with
  small supporting files if needed for regions or selector matching.
- `tools/CompactInspectionOptions.kt` owns MCP option/schema extraction.
- `cli/CompactInspectionOptions.kt` owns the reusable Clikt option group.
- Existing Android/IOS automation registrars invoke the common projection.
- `cli/commands/GetInteractiveElementsCommand.kt` selects the output mode.
- `cli/commands/FlowCommand.kt` and `cli/FlowObservationCollector.kt` forward
  observation options and preserve flow outcomes.
- Tests in `app/src/test/kotlin/com/example/visiontest/common`,
  `tools`, and `cli` cover their respective boundaries.
- `cli/CompactInspectionBehaviorTest.kt` executes the committed feature file,
  following FlowFeatureScenarios' exact scenario/step-binding discipline.
- Documentation and benchmark fixtures/report explain behavior and measured limits.

Kotlin paths above are under `app/src/main/kotlin/com/example/visiontest`.
Task 1 defines the API consumed by Tasks 2 and 3. Task 4 measures the final API.

## Task 1: Project fresh native observations

**Review:** checkpoint.

Use agentico:behavior-driven-development for
`app/src/test/resources/features/compact-inspection.feature`:

```gherkin
Scenario: Preserve target information in a compact observation
  Given native Android and iOS observations with distinct and duplicate targets
  When the caller requests compact elements
  Then observed selectors and relevant states remain available without repeated presentation fields
```

Write failing common tests and BDD bindings before implementation.
Expose `internal data class CompactInspectionOptions(val compact: Boolean = false,
val type: String? = null, val region: String? = null, val includeBounds: Boolean = false)`
with `fun validate()` and `fun project(response: String, ios: Boolean): String`.
Default projection returns the original response unchanged.
Implement the approved schema, validation, selector ordering, pre-filter matching
counts and half-open region rules. Unknown source fields stay available in full
mode. Failure objects remain unwrapped without success conversion.

The tests must assert native iOS text matches label OR value, duplicate matching
before filters, missing selector/fields, distinct accessibility/value preservation,
disabled and interaction states, bounds inclusion, malformed types and envelopes,
invalid/overflowing regions and exact boundaries. Example core expectation:

```kotlin
assertEquals(response, CompactInspectionOptions().project(response, ios = false))
assertFailsWith<IllegalArgumentException> {
    CompactInspectionOptions(region = "0,0,10,10").validate()
}
```

Run `./gradlew :app:test --tests '*CompactInspection*' -q`.
Expected RED before production changes, then PASS. Commit implementation and tests.

## Task 2: Expose compact inspection through shared operations

**Review:** checkpoint.

Use agentico:behavior-driven-development for:

```gherkin
Scenario: Request compact inspection through CLI and MCP
  Given a running automation server with interactive elements
  When the caller requests compact elements through CLI or MCP
  Then both interfaces return the same compact representation and reject invalid filters before device access
```

Append `options: CompactInspectionOptions = CompactInspectionOptions()` to both
registrar getInteractiveElements signatures, after existing arguments. Validate
before serverOperation and project the native response inside the operation.
Add MCP schema/extraction for compact/type/region/includeBounds while preserving
includeDisabled and iOS bundleId. Inspect ElementTapToolRegistrarTest for schema
and handler capture. Keep tool names and EXPECTED_TOOLS unchanged.
Add the Clikt group exposing `options()`, validate before lazy components, and
return compact JSON directly from the standalone command; keep full output
through inspectionOutput. `--json --compact` is accepted.

Use real clients with MockWebServer following ParityCliTest. Assert equal payloads
for equivalent CLI/MCP calls, preflight without lazy initialization, schema fields,
full-mode regressions and returned/malformed/RPC failure behavior.
Example validation expectation:

```kotlin
assertFalse(components.isInitialized())
assertEquals(2, result.exitCode)
assertNull(result.stdout)
```

Run `./gradlew :app:test --tests '*CompactInspection*' --tests '*ParityCliTest' -q`.
Observe RED, implement, PASS, and commit.

## Task 3: Integrate compact flow observations

**Review:** checkpoint.

Use agentico:behavior-driven-development for:

```gherkin
Scenario: Return compact observations from a flow
  Given a flow with requested interactive-element observations
  When the caller enables compact output
  Then final observations and failure evidence use the shared compact representation
```

Reuse the CLI group on FlowCommand and append options to the collector constructor
without breaking existing positional arguments. Validate all options before
FlowPreparation/device access; compact configuration requires interactive-elements
in observe or onFailure. Delegate to registrar options and parse compact objects
directly rather than using the full-schema formatter.
Use FlowObservationFixture for final success and failure evidence on both
platforms. Assert unchanged step outcomes, error certainty, original exit code,
scope forwarding, other observation kinds, text and JSON output, invalid filter
preflight and full-mode compatibility.
Make the BDD runner enforce all three scenario bindings.

Run `./gradlew :app:test --tests '*CompactInspection*' --tests '*Flow*' -q`.
Observe RED, implement, PASS, and commit.

## Task 4: Document and evaluate the delivered behavior

**Review:** checkpoint (final).

Update docs/cli-json.md, docs/cli-flow.md, docs/agentico/specs/cli.md,
README.md navigation, relevant MCP reference, and
app/src/main/resources/agent-instructions.md. The distributed skill must remain
self-contained and recommend compact observation, known sequence, explicit wait,
and requested final observation. Remove its now-obsolete jq workaround guidance.

Add reproducible synthetic Android/iOS fixtures and a comparison that exercises
the production projection, records full/compact bytes and expected target
preservation, and distinguishes unavailable actual agent/token/latency measurements.
If a named tokenizer is locally available, report estimates with its version;
otherwise report that token estimation is unavailable. Do not add dependencies.
Check available dedicated native fixtures for controlled flow execution, record
results if runnable, and explicitly report missing prerequisites otherwise.

Run all required gates: `./gradlew :app:test -q`,
`./gradlew :app:e2eTest -q`, `./gradlew build -q`, and `git diff --check`.
Do not lower coverage or alter lint baselines. Document results and limitations,
commit, then perform independent branch review against f956cb1.
