# OpenSpec to Agentico Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development (recommended) or agentico:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace OpenSpec with Agentico while preserving verified behavioral contracts, architectural rationale, and current operational documentation.

**Architecture:** Documentation is separated by purpose: current contracts live in `docs/agentico/specs/`, consequential rationale lives in `docs/decisions/`, active work lives in `docs/agentico/plans/`, and user or operator procedures live in focused guides. OpenSpec is removed only after each retained fact has a validated destination.

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
- Reference: `openspec/changes/archive/2026-03-24-refactor-toolfactory/design.md`
- Reference: `openspec/changes/add-cli-mode/design.md`
- Reference: `openspec/changes/archive/2026-03-20-ios-prebuilt-bundle/design.md`
- Reference: `openspec/changes/add-android-screenshot/design.md`
- Reference: `openspec/changes/archive/2026-04-16-add-ios-screenshot/design.md`
- Reference: `openspec/changes/stop-server-and-wait-tools/design.md`
- Reference: `openspec/changes/add-init-command/design.md`

- [ ] **Step 1: Verify the next Technical Decision identifiers**

Run:

```bash
find docs/decisions -maxdepth 1 -name 'TD-*.md' -print | sort
```

Expected: only `TD-001-adopt-agentico-for-spec-driven-work.md`; allocate TD-002 through TD-007 in the order listed above.

- [ ] **Step 2: Write the decision index**

Create `docs/decisions/README.md` with:

```markdown
# Technical Decisions

Technical Decisions record consequential project choices, their rationale, and alternatives. Specifications describe current behavior; implementation plans describe active work.

| ID | Status | Decision |
|---|---|---|
| [TD-001](TD-001-adopt-agentico-for-spec-driven-work.md) | Accepted | Adopt Agentico for spec-driven work |
| [TD-002](TD-002-modular-tool-registration-and-discovery.md) | Accepted | Modular tool registration and discovery |
| [TD-003](TD-003-share-handlers-between-mcp-and-cli.md) | Accepted | Share handlers between MCP and CLI |
| [TD-004](TD-004-prefer-prebuilt-ios-bundle-with-source-fallback.md) | Accepted | Prefer pre-built iOS bundle with source fallback |
| [TD-005](TD-005-persist-screenshots-on-the-host.md) | Accepted | Persist screenshots on the host |
| [TD-006](TD-006-poll-for-elements-in-the-client.md) | Accepted | Poll for elements in the client |
| [TD-007](TD-007-install-project-local-agent-instructions.md) | Accepted | Install project-local agent instructions |
```

- [ ] **Step 3: Write TD-002 through TD-007**

Use the exact frontmatter shape from TD-001. Each decision must contain `Context`, `Decision`, `Rationale`, `Consequences`, and `Alternatives Considered`, remain roughly 15–30 body lines, and record these choices:

```text
TD-002: ToolScope centralizes timeout/error handling; focused ToolRegistrar implementations own related tools; ToolDiscovery owns artifact and project resolution. Registrar count is extensible, not fixed.
TD-003: MCP registrations and CLI commands call shared internal suspend handlers so both facades preserve behavior without duplicating device logic.
TD-004: Installed pre-built iOS test bundles are preferred for startup speed and zero-config operation; a source project is the compatibility fallback.
TD-005: Automation servers return base64 PNG data and the Kotlin host resolves paths, creates directories, and atomically writes files.
TD-006: Kotlin JSON-RPC clients perform bounded 500 ms polling so appear/gone semantics and transport failures are consistent across Android and iOS.
TD-007: visiontest init writes embedded instructions to project-local agent directories; binary installation remains separate from project configuration.
```

- [ ] **Step 4: Self-review the decisions**

Run:

```bash
rg -n 'TBD|TODO|\[[^]]*(placeholder|insert|fill)[^]]*\]' docs/decisions
git diff --check
```

Expected: the first command has no matches and `git diff --check` exits 0.

- [ ] **Step 5: Commit the decisions**

```bash
git add docs/decisions
git commit -m "docs: preserve architectural decisions from OpenSpec"
```

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

- [ ] **Step 1: Extract implementation-backed requirements**

For each destination, compare the corresponding OpenSpec scenarios with current production code and tests. Record only requirements supported by code or an executable test. Treat unchecked OpenSpec manual checks as unverified evidence, not completed requirements.

Run these inventory commands before writing:

```bash
rg -n 'name = "|subcommands\(' app/src/main/kotlin/com/example/visiontest
rg -n 'EXPECTED_TOOLS|ui\.screenshot|pollForElement|findXctestrun|findAutomationServerApk' app automation-server ios-automation-server
rg -n 'visiontest\.jar|automation-server\.apk|automation-server-test\.apk|ios-automation-server\.tar\.gz' .github/workflows/release.yaml install.sh
```

Expected: source evidence exists for every retained command, tool, polling rule, discovery rule, and release artifact.

- [ ] **Step 2: Write the eight current-state specifications**

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

- [ ] **Step 3: Check specification consistency**

Run:

```bash
rg -n 'exactly four|13 subcommands|iPhone 16|TBD|TODO' docs/agentico/specs
rg -n '^### Requirement:|^#### Scenario:' docs/agentico/specs/{screenshots,cli,agent-init,server-lifecycle,element-waits,artifact-discovery,android-artifact-distribution,ios-bundle-distribution}.md
git diff --check
```

Expected: the first command has no matches; every new spec reports requirements and scenarios; `git diff --check` exits 0.

- [ ] **Step 4: Commit the migrated specifications**

```bash
git add docs/agentico/specs
git commit -m "docs: migrate current behavior specifications to Agentico"
```

### Task 3: Synchronize User and Agent Documentation

**Files:**

- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `AGENT_INSTRUCTIONS.md`
- Test: `app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt`
- Test: `app/src/test/kotlin/com/example/visiontest/cli/VisionTestCliTest.kt`

- [ ] **Step 1: Establish the canonical catalogs from executable contracts**

Run:

```bash
sed -n '/EXPECTED_TOOLS = setOf(/,/)/p' app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt
sed -n '/subcommands(/,/)/p' app/src/main/kotlin/com/example/visiontest/cli/VisionTestCli.kt
```

Expected: the MCP set contains both screenshot tools and all stop/wait tools; the CLI registers 16 subcommands including `init`.

- [ ] **Step 2: Update README.md**

Make these exact semantic changes:

```text
Add android_screenshot and ios_screenshot to Available Tools.
Add ui.screenshot to the JSON-RPC method table if that table remains in README.
Mark wait/sync operations complete or remove the completed future-plan item.
State that device-operation commands require --platform; init, --help, and --version do not.
Link detailed behavioral contracts to docs/agentico/specs/ instead of repeating low-level rules.
```

- [ ] **Step 3: Update CLAUDE.md**

Make these exact semantic changes:

```text
Replace "Every command requires" with the device-operation rule and explicit init/help/version exceptions.
Use vX.Y.Z in release-tag examples.
Use the CI simulator destination or instruct contributors to choose an installed simulator.
Add links to Technical Decisions and Agentico specifications under Further Reading.
Keep the MCP catalog aligned with EXPECTED_TOOLS.
```

- [ ] **Step 4: Update AGENT_INSTRUCTIONS.md**

Make these exact semantic changes:

```text
State the --platform exception for init/help/version.
Keep the embedded skill concise and operational; link neither repository-local docs nor OpenSpec because installed copies may run outside this checkout.
Ensure the command list includes wait, stop, screenshot, and init behavior accurately.
```

- [ ] **Step 5: Verify catalogs and stale claims**

Run:

```bash
rg -n 'Wait/sync operations|Every command requires|v0\.1\.0|exactly four|13 subcommands' README.md CLAUDE.md AGENT_INSTRUCTIONS.md
rg -n 'android_screenshot|ios_screenshot|wait_for_element|stop_automation_server' README.md CLAUDE.md AGENT_INSTRUCTIONS.md
git diff --check
```

Expected: the stale-claim search has no matches; the capability search finds each current operation; `git diff --check` exits 0.

- [ ] **Step 6: Commit user and agent documentation**

```bash
git add README.md CLAUDE.md AGENT_INSTRUCTIONS.md
git commit -m "docs: synchronize user and agent command references"
```

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

- [ ] **Step 1: Update contributor architecture and tests**

Update `CONTRIBUTING.md` to show 16 CLI subcommands and all seven registrars, include `ui.screenshot` and both screenshot MCP tools, list the current test files, and link `docs/release.md`, `docs/agentico/specs/`, and `docs/decisions/`.

Update `.github/copilot-instructions.md` from two main modules to three components: Kotlin MCP/CLI, Android automation server, and iOS automation server.

- [ ] **Step 2: Update installation documentation**

In `docs/installation.md`, state that only device-operation commands require `--platform`; keep `init` separate. Cross-check all downloaded assets, checksums, supported platforms, and install paths against `install.sh`.

- [ ] **Step 3: Create the canonical release guide**

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

- [ ] **Step 4: Clean local-only guidance**

Delete the obsolete `.claude/how-to-release.md` in favor of `docs/release.md`. Replace absolute `/Users/domenicocervo/Git/visiontest` paths in `.claude/how-to-test.md` with commands relative to the repository root. Delete `.claude/release-apk-plan.md` because its work is implemented and documented canonically.

- [ ] **Step 5: Clarify the Kotlin MCP guide**

Add a note near the top of `kotlin-mcp-server.instruction.md` stating that dependency versions and snippets are patterns, while `build.gradle.kts` files are the source of truth for this repository's actual configuration.

- [ ] **Step 6: Verify guide claims**

Run:

```bash
rg -n '/Users/domenicocervo|13 subcommands|four registrars|two main modules|v0\.1\.0|only prerequisite.*Java' CONTRIBUTING.md docs .claude .github kotlin-mcp-server.instruction.md
rg -n 'automation-server\.apk|automation-server-test\.apk|ios-automation-server\.tar\.gz' docs/release.md docs/installation.md .github/workflows/release.yaml install.sh
git diff --check
```

Expected: stale and machine-specific claims have no matches; every distributed artifact appears in release and installation evidence; `git diff --check` exits 0.

- [ ] **Step 7: Commit maintained guides**

```bash
git add CONTRIBUTING.md docs/installation.md docs/release.md kotlin-mcp-server.instruction.md .github/copilot-instructions.md
git add -u .claude
git commit -m "docs: refresh contributor and release guidance"
```

### Task 5: Retire Completed Agentico Working Documents

**Files:**

- Delete: `docs/agentico/plans/2026-08-31-android-stop-port-forward-cleanup.md`
- Delete: `docs/agentico/specs/2026-08-31-android-stop-port-forward-cleanup-design.md`
- Reference: `docs/agentico/specs/server-lifecycle.md`
- Reference: `docs/decisions/TD-002-modular-tool-registration-and-discovery.md`

- [ ] **Step 1: Confirm current documents preserve the durable content**

Run:

```bash
rg -n 'forward|cleanup|idempotent|stop' docs/agentico/specs/server-lifecycle.md docs/decisions
```

Expected: server lifecycle covers removal and verification of Android port forwards, and the decisions explain the registrar boundary where relevant.

- [ ] **Step 2: Remove completed working documents**

Delete the completed plan and its narrow design document. Their implementation history remains in Git and their current contract now lives in `server-lifecycle.md`.

- [ ] **Step 3: Verify no active plan claims completed work is pending**

Run:

```bash
find docs/agentico/plans -maxdepth 1 -type f -name '*.md' -print
```

Expected: only this migration plan remains while migration is active.

- [ ] **Step 4: Commit the retirement**

```bash
git add -u docs/agentico
git commit -m "docs: retire completed Agentico cleanup plan"
```

### Task 6: Remove OpenSpec and OPSX Integrations

**Files:**

- Delete: `openspec/`
- Delete: `.github/prompts/opsx-apply.prompt.md`
- Delete: `.github/prompts/opsx-archive.prompt.md`
- Delete: `.github/prompts/opsx-explore.prompt.md`
- Delete: `.github/prompts/opsx-propose.prompt.md`
- Delete: `.github/skills/openspec-apply-change/`
- Delete: `.github/skills/openspec-archive-change/`
- Delete: `.github/skills/openspec-explore/`
- Delete: `.github/skills/openspec-propose/`
- Delete locally: `.claude/commands/opsx/`
- Delete locally: `.claude/skills/openspec-apply-change/`
- Delete locally: `.claude/skills/openspec-archive-change/`
- Delete locally: `.claude/skills/openspec-explore/`
- Delete locally: `.claude/skills/openspec-propose/`
- Modify locally: `.claude/settings.local.json`

- [ ] **Step 1: Capture the exact removal inventory**

Run:

```bash
git ls-files 'openspec/**' '.github/prompts/opsx-*' '.github/skills/openspec-*/**'
rg -l -i -uuu 'openspec|open spec|opsx' --glob '!.git/**' --glob '!**/build/**' .
```

Expected: results are confined to the approved legacy workflow files plus the three temporary migration records.

- [ ] **Step 2: Remove tracked OpenSpec files**

Delete the tracked paths listed in this task. Do not remove Agentico specifications, decisions, general agent instructions, or application files.

- [ ] **Step 3: Remove ignored local OpenSpec files**

Delete only the exact `.claude/commands/opsx/` and `.claude/skills/openspec-*` paths listed above. From `.claude/settings.local.json`, remove only permission entries whose command begins with `openspec`; preserve every unrelated local permission.

- [ ] **Step 4: Verify the remaining references are intentional migration history**

Run:

```bash
rg -n -i -uuu 'openspec|open spec|opsx' --glob '!.git/**' --glob '!**/build/**' .
```

Expected: matches occur only in TD-001, the approved migration design, and this active migration plan. No operational prompt, skill, config, or capability spec remains. Task 7 removes these final textual and filename references.

- [ ] **Step 5: Commit tracked removals**

```bash
git add -u openspec .github
git commit -m "chore: remove OpenSpec workflow artifacts"
```

The ignored `.claude` cleanup is local-only and will not appear in the commit.

### Task 7: Verify Documentation and Build Integrity

**Files:**

- Modify if necessary: documentation files changed by Tasks 1–6
- Rename: `docs/agentico/specs/2026-08-31-openspec-to-agentico-migration-design.md` to `docs/agentico/specs/2026-08-31-legacy-spec-workflow-migration-design.md`
- Rename: `docs/agentico/plans/2026-08-31-openspec-to-agentico-migration.md` to `docs/agentico/plans/2026-08-31-legacy-spec-workflow-migration.md`
- Modify: `docs/decisions/TD-001-adopt-agentico-for-spec-driven-work.md`
- Test: all source Markdown links
- Test: `app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt`
- Test: `app/src/test/kotlin/com/example/visiontest/cli/VisionTestCliTest.kt`

- [ ] **Step 1: Check Markdown links**

Run a repository-relative Markdown link checker over all source `.md` files, excluding `.git`, generated build output, and ignored third-party/cache content.

Expected: zero missing local targets and zero invalid anchors for links changed by this migration.

- [ ] **Step 2: Check formatting and placeholders**

Run:

```bash
git diff --check main..HEAD
rg -n 'TBD|TODO|implement later|fill in details|exactly four|13 subcommands' docs README.md CLAUDE.md AGENT_INSTRUCTIONS.md CONTRIBUTING.md
```

Expected: `git diff --check` exits 0; the content search has no matches except legitimate roadmap items outside migrated contracts.

- [ ] **Step 3: Verify MCP and CLI executable contracts**

Run:

```bash
./gradlew :app:test --tests 'com.example.visiontest.McpStdioE2ETest' --tests 'com.example.visiontest.cli.VisionTestCliTest'
```

Expected: `BUILD SUCCESSFUL` with both test classes passing.

- [ ] **Step 4: Run the full project gate**

Run:

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`; tests, E2E tests, Kover verification, detekt, and Android lint pass.

- [ ] **Step 5: Remove final legacy product-name references**

Rename the migration design and this plan to the `legacy-spec-workflow` filenames listed above. Rewrite TD-001, the migration design, and the plan so they refer to the removed system as the “legacy spec workflow”; preserve the reason for adopting Agentico without retaining the removed product name or OPSX command prefix.

Run:

```bash
find . -path './.git' -prune -o -path '*/build' -prune -o -iname '*openspec*' -print
rg -n -i -uuu 'openspec|open spec|opsx' --glob '!.git/**' --glob '!**/build/**' .
```

Expected: both commands produce no output.

- [ ] **Step 6: Verify repository scope**

Run:

```bash
git status --short
git diff --stat main..HEAD
```

Expected: the working tree contains only the final plan update before its commit; the diff contains documentation and approved workflow-file removals only.

- [ ] **Step 7: Mark this plan complete**

Change every completed checkbox in this plan from `[ ]` to `[x]`. Keep the migration plan as the execution record until branch integration; after integration, Git history preserves it and the project may archive or remove it according to the documentation model.

- [ ] **Step 8: Commit verification records**

```bash
git add docs/agentico/plans/2026-08-31-legacy-spec-workflow-migration.md docs/agentico/specs/2026-08-31-legacy-spec-workflow-migration-design.md docs/decisions/TD-001-adopt-agentico-for-spec-driven-work.md
git commit -m "docs: record completed Agentico migration"
```
