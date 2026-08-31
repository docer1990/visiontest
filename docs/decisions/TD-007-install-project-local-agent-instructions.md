---
id: TD-007
title: "Install project-local agent instructions"
status: accepted
date: 2026-08-31
supersedes: null
superseded_by: null
tags: [cli, agents, installation]
---

# TD-007: Install project-local agent instructions

## Context

Binary installation and AI-agent configuration have different lifecycles. Global home-directory instructions cannot be reviewed with a project, and downloading instructions during setup risks version skew between the CLI and its guidance.

## Decision

`visiontest init --agent` writes embedded VisionTest instructions into the selected project-local agent directories for Claude Code, OpenCode, and Codex. The JAR embeds one instruction source, each target receives the same `SKILL.md` content, and binary installation remains separate.

## Rationale

Project-local files are versionable, visible to collaborators, and scoped to repositories that use VisionTest. Embedding the instructions keeps initialization offline and aligned with the installed binary while one shared format avoids per-agent content drift.

## Consequences

- **Positive:** Projects can review and share their VisionTest agent setup.
- **Positive:** Re-running initialization deterministically refreshes selected agent files.
- **Negative:** Users must run `init` in each project and again after instruction updates.
- **Negative:** Agent directory conventions must be maintained as external tools evolve.

## Alternatives Considered

### Install global instructions with the binary

Rejected because it mixes machine-wide configuration with binary distribution and bypasses project version control.

### Download instructions during initialization

Rejected because it introduces network failure and version-mismatch modes for a small bundled resource.
