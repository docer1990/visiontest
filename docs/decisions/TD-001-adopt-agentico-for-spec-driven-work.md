---
id: TD-001
title: "Adopt Agentico for spec-driven work"
status: accepted
date: 2026-08-31
supersedes: null
superseded_by: null
tags: [documentation, workflow, agentico]
---

# TD-001: Adopt Agentico for spec-driven work

## Context

VisionTest currently stores product requirements, designs, task lists, and archived changes in OpenSpec. The repository also contains OpenSpec-specific prompts and skills for multiple agents. Agentico is now the active development workflow, and keeping both systems creates duplicate sources of truth and allows completed task lists and capability specifications to drift from the code.

## Decision

VisionTest will use Agentico specifications and plans for future work, Technical Decisions for durable rationale, and focused user or operational guides for current usage. OpenSpec artifacts and integrations will be removed after their still-valid content has been consolidated into those destinations.

## Rationale

A selective migration preserves current behavioral contracts and architectural rationale without carrying forward historical proposals, completed task lists, or obsolete requirements. It also gives each document one purpose: specifications describe observable behavior, plans track active implementation, TDs explain consequential choices, and guides describe operation.

## Consequences

- **Positive:** One development workflow and a smaller, current documentation set.
- **Positive:** Durable decisions remain searchable without duplicating implementation details.
- **Negative:** The migration requires validating OpenSpec claims against the implementation rather than copying files mechanically.
- **Negative:** Historical proposal and task narratives will be available through Git history instead of the working tree.

## Alternatives Considered

### Literal OpenSpec migration

Rejected because it would retain duplication, completed checklists, and requirements already contradicted by the code.

### Minimal documentation cleanup

Rejected because it would discard useful contracts for discovery precedence, fallback behavior, timeouts, exit codes, and artifact distribution.
