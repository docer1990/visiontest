---
id: TD-009
title: "Separate repository guidance from the distributed skill"
status: accepted
date: 2026-09-08
supersedes: TD-007
superseded_by: null
tags: [cli, agents, documentation]
---

# TD-009: Separate repository guidance from the distributed skill

## Context

TD-007 made the repository-root `AGENTS.md` serve both as guidance for agents
changing VisionTest and as the skill installed into unrelated client projects.
Those audiences need different context. Repository paths in the shared file also
become invalid after `visiontest init` copies it elsewhere.

## Decision

`AGENTS.md` guides coding agents working in the VisionTest repository.
`app/src/main/resources/agent-instructions.md` is the self-contained source
embedded in the JAR and written as each generated `SKILL.md`. Init prepends the
shared YAML frontmatter at runtime.

## Rationale

Separate sources keep repository conventions out of client projects and let the
distributed skill focus on end-to-end mobile testing. Keeping the skill in
regular application resources preserves offline, version-aligned initialization
without a custom Gradle copy step.

## Consequences

- **Positive:** Each document has one audience; an E2E packaging check rejects
  VisionTest repository-only references in the generated skill.
- **Positive:** The packaged resource follows the standard Gradle resource path.
- **Negative:** Public command changes may require coordinated edits to both
  repository guidance and the distributed skill.

## Alternatives Considered

### Keep one shared AGENTS.md

Rejected because repository development rules and client-project test guidance
compete for space and require incompatible links and context.
