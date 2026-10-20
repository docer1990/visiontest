---
id: TD-017
title: "Project compact observations in shared operations"
status: accepted
date: 2026-10-20
supersedes: null
superseded_by: null
tags: [inspection, cli, mcp, agents]
---

# TD-017: Project compact observations in shared operations

## Context
Agents need less repeated UI context and selectors they can pass to actions.
Installed native servers already provide the fields required for this view.
Native selector arguments use precedence rather than conjunction.

## Decision
Project an opt-in versioned compact representation in shared Kotlin operations.
Return a single observed selector and identify duplicates before host filtering.

## Rationale
Following TD-003 gives CLI, MCP and flow the same semantics without requiring
native bundle upgrades. Keeping native type names avoids inferred roles.

## Consequences
The view reduces agent payload, but does not reduce native acquisition work.
Full observations remain available. Observed matches cannot prove global
uniqueness, and interactions still resolve current native state.

## Alternatives considered
Native projection would require coordinated server upgrades for a presentation
change. CLI-only filtering would leave MCP and flow behavior inconsistent.
Combining selectors would incorrectly imply conjunction in current backends.
