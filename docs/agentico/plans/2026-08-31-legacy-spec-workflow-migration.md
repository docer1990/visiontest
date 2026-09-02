# Legacy spec workflow to Agentico Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development (recommended) or agentico:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace legacy spec workflow with Agentico while preserving verified behavioral contracts, architectural rationale, and current operational documentation.

**Architecture:** Documentation is separated by purpose: current contracts live in `docs/agentico/specs/`, consequential rationale lives in `docs/decisions/`, active work lives in `docs/agentico/plans/`, and user or operator procedures live in focused guides. The legacy spec workflow was removed only after each retained fact had a validated destination.

**Tech Stack:** Markdown, Git, ripgrep, Gradle, Kotlin/JVM, GitHub Actions

**Decision:** [TD-001](../../decisions/TD-001-adopt-agentico-for-spec-driven-work.md)

---

### Task 1: Establish the Technical Decision Index and Architecture Decisions

**Files:**

- Create: `docs/decisions/README.md`
- Create: `docs/decisions/TD-002-modular-tool-registration-and-discovery.md`
- Create: `docs/decisions/TD-003-share-handlers-between-mcp-and-cli.md`
- Create: `docs/decisions/TD-004-prefer-prebuilt-ios-bundle-with-source-fallback.md`
- Create: `docs/decisions/TD-005-persist-screenshots-on-the-host.md`
- Create: `docs/decisions/TD-006-poll-for-elements-in-the-client.md`
- Create: `docs/decisions/TD-007-install-project-local-agent-instructions.md`
- Historical source: legacy workflow design for tool registration and discovery
- Historical source: legacy workflow design for CLI mode
- Historical source: legacy workflow design for iOS pre-built bundles
- Historical source: legacy workflow design for Android screenshots
- Historical source: legacy workflow design for iOS screenshots
- Historical source: legacy workflow design for server stop and element waits
- Historical source: legacy workflow design for project-local agent initialization

- [x] **Step 1: Verify the next Technical Decision identifiers**

Run:

```bash
find docs/decisions -maxdepth 1 -name 'TD-*.md' -print | sort
```

Expected: only `TD-001-adopt-agentico-for-spec-driven-work.md`; allocate TD-002 through TD-007 in the order listed above.

- [x] **Step 2: Write the decision index**

Create `docs/decisions/README.md` with:

```markdown
# Technical Decisions

Technical Decisions record consequential project choices, their rationale, and alternatives. Specifications describe current behavior; implementation plans describe active work.

| ID | Status | Decision |
|---|---|---|
| [TD-001](../../decisions/TD-001-adopt-agentico-for-spec-driven-work.md) | Accepted | Adopt Agentico for spec-driven work |
| [TD-002](../../decisions/TD-002-modular-tool-registration-and-discovery.md) | Accepted | Modular tool registration and discovery |
| [TD-003](../../decisions/TD-003-share-handlers-between-mcp-and-cli.md) | Accepted | Share handlers between MCP and CLI |
| [TD-004](../../decisions/TD-004-prefer-prebuilt-ios-bundle-with-source-fallback.md) | Accepted | Prefer pre-built iOS bundle with source fallback |
| [TD-005](../../decisions/TD-005-persist-screenshots-on-the-host.md) | Accepted | Persist screenshots on the host |
| [TD-006](../../decisions/TD-006-poll-for-elements-in-the-client.md) | Accepted | Poll for elements in the client |
| [TD-007](../../decisions/TD-007-install-project-local-agent-instructions.md) | Accepted | Install project-local agent instructions |
```

- [x] **Step 3: Write TD-002 through TD-007**

Use the exact frontmatter shape from TD-001. Each decision must contain `Context`, `Decision`, `Rationale`, `Consequences`, and `Alternatives Considered`, remain roughly 15–30 body lines, and record these choices:

```text
TD-002: ToolScope centralizes timeout/error handling; focused ToolRegistrar implementations own related tools; ToolDiscovery owns artifact and project resolution. Registrar count is extensible, not fixed.
TD-003: MCP registrations and CLI commands call shared internal suspend handlers so both facades preserve behavior without duplicating device logic.
TD-004: Installed pre-built iOS test bundles are preferred for startup speed and zero-config operation; a source project is the compatibility fallback.
TD-005: Automation servers return base64 PNG data and the Kotlin host resolves paths, creates directories, and atomically writes files.
TD-006: Kotlin JSON-RPC clients perform bounded 500 ms polling so appear/gone semantics and transport failures are consistent across Android and iOS.
TD-007: visiontest init writes embedded instructions to project-local agent directories; binary installation remains separate from project configuration.
```

- [x] **Step 4: Self-review the decisions**

The placeholder scan returned no matches, and `git diff --check` exited 0.

- [x] **Step 5: Commit the decisions**

Recorded by commit `7bf0f12`; follow-up review fixes are recorded in later task commits.

### Task 2: Migrate Current Behavioral Contracts

**Files:**

- Create: `docs/agentico/specs/screenshots.md`
- Create: `docs/agentico/specs/cli.md`
- Create: `docs/agentico/specs/agent-init.md`
- Create: `docs/agentico/specs/server-lifecycle.md`
- Create: `docs/agentico/specs/element-waits.md`
- Create: `docs/agentico/specs/artifact-discovery.md`
- Create: `docs/agentico/specs/android-artifact-distribution.md`
- Create: `docs/agentico/specs/ios-bundle-distribution.md`
- Reference: `app/src/main/kotlin/com/example/visiontest/cli/`
- Reference: `app/src/main/kotlin/com/example/visiontest/discovery/ToolDiscovery.kt`
- Reference: `app/src/main/kotlin/com/example/visiontest/tools/`
- Reference: `app/src/test/kotlin/com/example/visiontest/`
- Reference: `automation-server/src/main/`
- Reference: `automation-server/src/androidTest/`
- Reference: `ios-automation-server/IOSAutomationServerUITests/`
- Reference: `.github/workflows/release.yaml`
- Reference: `install.sh`

- [x] **Step 1: Extract implementation-backed requirements**

For each destination, compare the corresponding legacy spec workflow scenarios with current production code and tests. Record only requirements supported by code or an executable test. Treat unchecked legacy spec workflow manual checks as unverified evidence, not completed requirements.

Run these inventory commands before writing:

```bash
rg -n 'name = "|subcommands\(' app/src/main/kotlin/com/example/visiontest
rg -n 'EXPECTED_TOOLS|ui\.screenshot|pollForElement|findXctestrun|findAutomationServerApk' app automation-server ios-automation-server
rg -n 'visiontest\.jar|automation-server\.apk|automation-server-test\.apk|ios-automation-server\.tar\.gz' .github/workflows/release.yaml install.sh
```

Expected: source evidence exists for every retained command, tool, polling rule, discovery rule, and release artifact.

- [x] **Step 2: Write the eight current-state specifications**

Use this structure in every file:

```markdown
# Capability name

## Purpose

One paragraph defining the capability and its boundary.

## Requirements

### Requirement: Observable behavior

Normative statement using MUST, MUST NOT, or SHALL.

#### Scenario: Concrete outcome

- **Given** an explicit starting state
- **When** the operation occurs
- **Then** the observable result is stated

## Verification

Exact production and test files that enforce the contract.
```

Required coverage:

```text
screenshots.md: Android/iOS JSON-RPC method, MCP names, optional/blank paths, default CWD path, parent creation, overwrite, atomic move fallback, invalid base64, stopped/outdated server.
cli.md: no-argument MCP mode, serve sentinel, 16 subcommands, platform exceptions, stdout/stderr, exit codes 0–5, shared handlers.
agent-init.md: claude/opencode/codex mappings, comma-separated values, embedded offline resource, overwrite idempotency, invalid and missing agents, no platform flag.
server-lifecycle.md: Android force-stop and forward cleanup, cleanup verification, idempotency, iOS process stop, CLI/MCP exposure.
element-waits.md: Android/iOS selectors, 500 ms polling, 10 s default, 30 s maximum, appear/gone results, malformed JSON-RPC and server failures.
artifact-discovery.md: environment override, source-tree search, install directory, main/test APK relationship, Xcode project, xctestrun, project-root bounds, precedence.
android-artifact-distribution.md: APK release assets and checksums, installer verification, installed names, supported platforms, failure behavior.
ios-bundle-distribution.md: arm64 macOS build and installation, archive contents, checksums, __TESTROOT__ portability, discovery, prebuilt-first startup, source fallback, compatibility failure.
```

- [x] **Step 3: Check specification consistency**

The stale-claim scan returned no matches, every new spec reported requirements and scenarios, and `git diff --check` exited 0.

- [x] **Step 4: Commit the migrated specifications**

Recorded by commit `7349e04`; contract corrections from review are recorded by `893edf4` and `4b6bd5b`.

### Task 3: Synchronize User and Agent Documentation

**Files:**

- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `AGENTS.md`
- Test: `app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt`
- Test: `app/src/test/kotlin/com/example/visiontest/cli/VisionTestCliTest.kt`

- [x] **Step 1: Establish the canonical catalogs from executable contracts**

Run:

```bash
sed -n '/EXPECTED_TOOLS = setOf(/,/)/p' app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt
sed -n '/subcommands(/,/)/p' app/src/main/kotlin/com/example/visiontest/cli/VisionTestCli.kt
```

Expected: the MCP set contains both screenshot tools and all stop/wait tools; the CLI registers 16 subcommands including `init`.

- [x] **Step 2: Update README.md**

Make these exact semantic changes:

```text
Add android_screenshot and ios_screenshot to Available Tools.
Add ui.screenshot to the JSON-RPC method table if that table remains in README.
Mark wait/sync operations complete or remove the completed future-plan item.
State that device-operation commands require --platform; init, --help, and --version do not.
Link detailed behavioral contracts to docs/agentico/specs/ instead of repeating low-level rules.
```

- [x] **Step 3: Update CLAUDE.md**

Make these exact semantic changes:

```text
Replace "Every command requires" with the device-operation rule and explicit init/help/version exceptions.
Use vX.Y.Z in release-tag examples.
Use the CI simulator destination or instruct contributors to choose an installed simulator.
Add links to Technical Decisions and Agentico specifications under Further Reading.
Keep the MCP catalog aligned with EXPECTED_TOOLS.
```

- [x] **Step 4: Update AGENTS.md**

Make these exact semantic changes:

```text
State the --platform exception for init/help/version.
Keep the embedded skill concise and operational; link neither repository-local docs nor legacy spec workflow because installed copies may run outside this checkout.
Ensure the command list includes wait, stop, screenshot, and init behavior accurately.
```

- [x] **Step 5: Verify catalogs and stale claims**

The stale-claim search returned no matches, the capability search found each current operation, and `git diff --check` exited 0.

- [x] **Step 6: Commit user and agent documentation**

Recorded by commits `2bdb85f`, `1d7f022`, and `90666b6`, including the standard instruction filename and CLI subset corrections.

### Task 4: Synchronize Contributor, Installation, Release, and Testing Guides

**Files:**

- Modify: `CONTRIBUTING.md`
- Modify: `docs/installation.md`
- Create: `docs/release.md`
- Modify: `kotlin-mcp-server.instruction.md`
- Modify: `.github/copilot-instructions.md`
- Delete: `.claude/how-to-release.md`
- Modify: `.claude/how-to-test.md`
- Delete: `.claude/release-apk-plan.md`
- Reference: `.github/workflows/pull-request-tests.yaml`
- Reference: `.github/workflows/release.yaml`
- Reference: `.github/workflows/android-emulator-smoke.yaml`

- [x] **Step 1: Update contributor architecture and tests**

Update `CONTRIBUTING.md` to show 16 CLI subcommands and all seven registrars, include `ui.screenshot` and both screenshot MCP tools, list the current test files, and link `docs/release.md`, `docs/agentico/specs/`, and `docs/decisions/`.

Update `.github/copilot-instructions.md` from two main modules to three components: Kotlin MCP/CLI, Android automation server, and iOS automation server.

- [x] **Step 2: Update installation documentation**

In `docs/installation.md`, state that only device-operation commands require `--platform`; keep `init` separate. Cross-check all downloaded assets, checksums, supported platforms, and install paths against `install.sh`.

- [x] **Step 3: Create the canonical release guide**

Create `docs/release.md` with these sections and commands:

````markdown
# Release Process

## Prerequisites

- Clean working tree
- Version updated consistently in project manifests
- GitHub CLI authentication when inspecting remote checks

## Local verification

```bash
./gradlew build
```

## Publish

```bash
git tag vX.Y.Z
git push origin vX.Y.Z
```

## Pipeline outputs

Document the JAR, two APKs, iOS bundle, installer, launcher, and every `.sha256` asset exactly as produced by `.github/workflows/release.yaml`.

## Verification

```bash
gh run list --workflow release.yaml
gh release view vX.Y.Z
```

## Recovery

Document how to diagnose a failed workflow; do not recommend deleting a published release or tag without an explicit rollback decision.
````

- [x] **Step 4: Clean local-only guidance**

Delete the obsolete `.claude/how-to-release.md` in favor of `docs/release.md`. Replace absolute `/Users/domenicocervo/Git/visiontest` paths in `.claude/how-to-test.md` with commands relative to the repository root. Delete `.claude/release-apk-plan.md` because its work is implemented and documented canonically.

- [x] **Step 5: Clarify the Kotlin MCP guide**

Add a note near the top of `kotlin-mcp-server.instruction.md` stating that dependency versions and snippets are patterns, while `build.gradle.kts` files are the source of truth for this repository's actual configuration.

- [x] **Step 6: Verify guide claims**

The stale and machine-specific claim scan returned no matches, every distributed artifact appeared in the release and installation evidence, and `git diff --check` exited 0.

- [x] **Step 7: Commit maintained guides**

Recorded by commits `568b332` and `8b39ce4`, including command and installer-safety corrections from review.

### Task 5: Retire Completed Agentico Working Documents

**Files:**

- Delete: `docs/agentico/plans/2026-08-31-android-stop-port-forward-cleanup.md`
- Delete: `docs/agentico/specs/2026-08-31-android-stop-port-forward-cleanup-design.md`
- Reference: `docs/agentico/specs/server-lifecycle.md`
- Reference: `docs/decisions/TD-002-modular-tool-registration-and-discovery.md`

- [x] **Step 1: Confirm current documents preserve the durable content**

Run:

```bash
rg -n 'forward|cleanup|idempotent|stop' docs/agentico/specs/server-lifecycle.md docs/decisions
```

Expected: server lifecycle covers removal and verification of Android port forwards, and the decisions explain the registrar boundary where relevant.

- [x] **Step 2: Remove completed working documents**

Delete the completed plan and its narrow design document. Their implementation history remains in Git and their current contract now lives in `server-lifecycle.md`.

- [x] **Step 3: Verify no active plan claims completed work is pending**

Run:

```bash
find docs/agentico/plans -maxdepth 1 -type f -name '*.md' -print
```

Expected: only this migration plan remains while migration is active.

- [x] **Step 4: Commit the retirement**

Recorded by commit `fa173fc`.

### Task 6: Remove legacy spec workflow integrations

**Files:**

- Delete: tracked legacy workflow documents and configuration
- Delete: tracked prompt definitions for the retired command prefix
- Delete: tracked agent skills dedicated to the legacy workflow
- Delete locally: ignored command definitions and agent skills dedicated to the legacy workflow
- Modify locally: `.claude/settings.local.json`

- [x] **Step 1: Capture the exact removal inventory**

The tracked-file and repository-content inventories were captured before deletion. Results were confined to the approved legacy workflow files plus the three temporary migration records.

- [x] **Step 2: Remove tracked legacy spec workflow files**

Delete the tracked paths in the captured inventory. Do not remove Agentico specifications, decisions, general agent instructions, or application files.

- [x] **Step 3: Remove ignored local legacy spec workflow files**

Delete only the ignored local command and skill paths in the captured inventory. From `.claude/settings.local.json`, remove only permission entries dedicated to the legacy workflow; preserve every unrelated local permission.

- [x] **Step 4: Verify the remaining references are intentional migration history**

The post-removal scan found retired product-name and command-prefix references only in TD-001, the approved migration design, and this active migration plan. No operational prompt, skill, config, or capability spec remained; Task 7 removed those final references.

- [x] **Step 5: Commit tracked removals**

Recorded by commit `1c08aa0`.

The ignored `.claude` cleanup is local-only and will not appear in the commit.

### Task 7: Verify Documentation and Build Integrity

**Files:**

- Modify if necessary: documentation files changed by Tasks 1–6
- Rename the migration design to `docs/agentico/specs/2026-08-31-legacy-spec-workflow-migration-design.md`
- Rename the active plan to `docs/agentico/plans/2026-08-31-legacy-spec-workflow-migration.md`
- Modify: `docs/decisions/TD-001-adopt-agentico-for-spec-driven-work.md`
- Test: all source Markdown links
- Test: `app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt`
- Test: `app/src/test/kotlin/com/example/visiontest/cli/VisionTestCliTest.kt`

- [x] **Step 1: Check Markdown links**

Run a repository-relative Markdown link checker over all source `.md` files, excluding `.git`, generated build output, and ignored third-party/cache content.

Expected: zero missing local targets and zero invalid anchors for links changed by this migration.

Execution record: a read-only Ruby checker parsed inline and reference links, resolved repository-relative targets, and validated GitHub-style Markdown anchors. It checked 46 Markdown files, 45 local links, and 8 anchor links with 0 errors. Seven broken TD links in this plan's decision-index example were corrected to resolve through `../../decisions/`.

- [x] **Step 2: Check formatting and placeholders**

Run `git diff --check main..HEAD` and scan maintained documentation for placeholders, deferred-work markers, and obsolete fixed-count claims.

Expected: the formatting check exits 0 and the content scan has no matches except legitimate roadmap items outside migrated contracts.

Execution record: both `git diff --check` and the maintained-document stale-fact scan completed with no findings.

- [x] **Step 3: Verify MCP and CLI executable contracts**

The combined `:app:test` selection succeeded but executed only `VisionTestCliTest` because `McpStdioE2ETest` is E2E-tagged. The executable contracts were therefore verified with the correct task split:

```bash
./gradlew :app:e2eTest --tests 'com.example.visiontest.McpStdioE2ETest'
./gradlew :app:test --tests 'com.example.visiontest.cli.VisionTestCliTest'
```

Execution record: both commands reported `BUILD SUCCESSFUL`; the E2E class ran 2 tests and the CLI class ran 13 tests, with 0 failures, errors, or skips.

- [x] **Step 4: Run the full project gate**

Run:

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`; tests, E2E tests, Kover verification, detekt, and Android lint pass.

Execution record: `./gradlew build` reported `BUILD SUCCESSFUL` with 132 actionable tasks, including unit tests, E2E tests, Kover verification, detekt, and Android lint.

- [x] **Step 5: Remove final legacy product-name references**

Rename the migration design and this plan to the `legacy-spec-workflow` filenames listed above. Rewrite TD-001, the migration design, and the plan so they refer to the removed system as the “legacy spec workflow”; preserve the reason for adopting Agentico without retaining the retired product name or command prefix.

Run repository-wide filename and case-insensitive content scans for the retired product name and command prefix, excluding `.git` and generated build output.

Expected: both commands produce no output.

Execution record: both scans produced no output. Empty directories left after tracked-file removal were verified to contain no files and removed before the final scan.

- [x] **Step 6: Verify repository scope**

Run:

```bash
git status --short
git diff --stat main..HEAD
```

Expected: the working tree contains only the final plan update before its commit; the diff contains documentation and approved workflow-file removals only.

Execution record: the branch diff contains the planned documentation and workflow removals plus `app/build.gradle.kts` and `InitCommandE2ETest.kt`, which keep the embedded agent instructions wired to the renamed `AGENTS.md` file. No generated build output is tracked.

- [x] **Step 7: Mark this plan complete**

Change every completed checkbox in this plan from `[ ]` to `[x]`. Keep the migration plan as the execution record until branch integration; after integration, Git history preserves it and the project may archive or remove it according to the documentation model.

- [x] **Step 8: Commit verification records**

```bash
git add -A docs/agentico/plans docs/agentico/specs
git add docs/decisions/TD-001-adopt-agentico-for-spec-driven-work.md
git commit -m "docs: complete Agentico documentation migration"
```
