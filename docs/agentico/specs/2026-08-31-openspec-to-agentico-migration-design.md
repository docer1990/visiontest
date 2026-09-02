# OpenSpec to Agentico documentation migration

## Goal

Replace OpenSpec with Agentico as VisionTest's sole spec-driven workflow while preserving current behavioral contracts, architectural rationale, and useful operational guidance. At completion, the repository must contain no OpenSpec or OPSX references.

## Current state

The audit covered 88 source Markdown files and excluded three generated reports under build directories. OpenSpec is isolated to documentation and agent configuration: no application, test, build, installer, or launcher code depends on it.

The documentation is not fully synchronized with the implementation. The most important discrepancies are:

- screenshot tools and `ui.screenshot` are missing from public tool and JSON-RPC catalogs;
- wait operations are implemented but still listed as future work;
- several guides incorrectly state that every CLI command requires `--platform`, despite the `init`, help, and version exceptions;
- the contributor architecture lists 13 CLI commands and four registrars instead of the current 16 commands and seven registrars;
- the local release guide predates Android APK and iOS bundle assets;
- the canonical OpenSpec tool-registration specification requires exactly four registrars and is obsolete.

## Documentation model

Each retained fact will have one destination based on its purpose:

- `docs/agentico/specs/` describes current, externally observable or operationally significant behavior;
- `docs/agentico/plans/` contains only active implementation plans;
- `docs/decisions/` records consequential choices and rejected alternatives;
- root documents and focused guides describe user, contributor, installation, testing, and release workflows;
- Git history, rather than files in the working tree, preserves completed proposals and task narratives.

Catalogs such as MCP tools, CLI commands, and JSON-RPC methods must have one canonical detailed representation. Other documents should link to it or provide a deliberately shorter overview.

## OpenSpec content migration

The migration will consolidate validated content as follows:

| OpenSpec subject | Destination | Treatment |
|---|---|---|
| Android and iOS screenshots | `docs/agentico/specs/screenshots.md` | Merge platform contracts; retain path, error, base64, and atomic-write semantics that match code |
| CLI mode | `docs/agentico/specs/cli.md` | Describe the current 16-command interface, platform rules, output channels, and exit codes |
| Agent initialization | `docs/agentico/specs/agent-init.md` | Retain supported agents, paths, offline resource, overwrite behavior, and validation |
| Server stop lifecycle | `docs/agentico/specs/server-lifecycle.md` | Retain idempotency, cleanup, verification, and platform behavior |
| Element waits | `docs/agentico/specs/element-waits.md` | Retain selectors, timeout bounds, polling, malformed-response, and server-failure behavior |
| Artifact and project discovery | `docs/agentico/specs/artifact-discovery.md` | Consolidate Android APK, Xcode project, install directory, and xctestrun precedence |
| Android artifact distribution | `docs/agentico/specs/android-artifact-distribution.md` | Retain release assets, checksums, installation, and installed-name discovery |
| iOS bundle distribution | `docs/agentico/specs/ios-bundle-distribution.md` | Consolidate build, archive, install, discovery, compatibility, and source fallback |
| Tool registration and discovery architecture | `docs/decisions/TD-002-*.md` | Preserve rationale for `ToolScope`, modular registrars, and `ToolDiscovery`; remove fixed registrar count |
| MCP and CLI dual facade | `docs/decisions/TD-003-*.md` | Preserve shared-handler rationale and compatibility constraints |
| Pre-built iOS bundle fallback | `docs/decisions/TD-004-*.md` | Preserve prebuilt-first/source-fallback choice |
| Screenshot persistence | `docs/decisions/TD-005-*.md` | Preserve host-side file ownership and atomic-write rationale |
| Client-side element polling | `docs/decisions/TD-006-*.md` | Preserve failure semantics and timeout rationale |
| Project-local agent instructions | `docs/decisions/TD-007-*.md` | Preserve embedded-resource and project-local configuration choice |

Proposal files, completed task lists, duplicate archived specifications, empty OpenSpec configuration, and superseded requirements will not be migrated.

## Existing documentation updates

The implementation must also:

- update `README.md`, `CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md`, and `docs/installation.md` to match current CLI and tool behavior;
- replace the obsolete local release guide with `docs/release.md` and link it from contributor documentation;
- make simulator examples resilient to installed Xcode versions or align them explicitly with CI;
- remove personal absolute paths from local testing guidance;
- label `kotlin-mcp-server.instruction.md` as patterns and examples rather than the project's literal dependency configuration;
- resolve completed Agentico plans so unchecked boxes do not imply pending work.

## Removal scope

After the retained content exists and has been reviewed, remove:

- `openspec/` in full;
- `.github/prompts/opsx-*.prompt.md`;
- `.github/skills/openspec-*/`;
- ignored local `.claude/commands/opsx/` and `.claude/skills/openspec-*/` content;
- OpenSpec command permissions in `.claude/settings.local.json`.

Deletion of ignored local files is intentional and part of the approved scope. No application code or runtime configuration is removed.

## Verification

The migration is complete only when:

1. a case-insensitive repository search, including hidden and ignored files but excluding `.git` and generated build output, finds no `openspec`, `open spec`, or `opsx` reference;
2. every migrated specification is checked against current code and tests rather than copied from task completion markers;
3. documented MCP tools match the E2E contract and registrar names;
4. documented CLI commands match `VisionTestCli` and its platform requirements;
5. documented release assets match `.github/workflows/release.yaml` and `install.sh`;
6. all relative Markdown links resolve;
7. documentation formatting checks pass;
8. the normal project build remains green, demonstrating that removal did not affect packaged resources or build inputs.

## Scope boundaries

This migration does not change application behavior, public command contracts, dependencies, release contents, or CI policy. If validation reveals a code defect rather than a documentation discrepancy, it will be reported separately and not silently changed as part of this work.
