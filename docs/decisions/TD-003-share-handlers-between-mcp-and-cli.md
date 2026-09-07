---
id: TD-003
title: "Share handlers between MCP and CLI"
status: accepted
date: 2026-08-31
supersedes: null
superseded_by: null
tags: [mcp, cli, tools]
---

# TD-003: Share handlers between MCP and CLI

## Context

VisionTest exposes automation operations through MCP and a command-line interface. Both surfaces need the same device checks, client calls, response parsing, and user-facing results, but MCP request extraction and framing differ from CLI option parsing and exit handling.

## Decision

Operations exposed through both surfaces call the same internal suspend handlers on the focused registrars. MCP registrations extract `CallToolRequest` arguments and delegate through `ToolScope`; CLI commands parse typed arguments and delegate through `ComponentHolder`.

## Rationale

Sharing the behavior-bearing handler preserves platform semantics without duplicating device logic. Thin adapters let MCP retain its timeout and error contract while CLI retains its stdout, stderr, and exit-code contract.

## Consequences

- **Positive:** Fixes to device behavior apply consistently to MCP and CLI callers.
- **Positive:** Adapter tests can focus on argument and error translation rather than re-testing device logic.
- **Negative:** Internal registrar handlers form a shared seam that both front ends depend on.
- **Negative:** CLI wiring constructs registrars even though it does not register MCP tools.

## Alternatives Considered

### Duplicate CLI implementations

Rejected because behavior and error messages would drift between the two surfaces.

### Extract a separate core module

Rejected because the additional module boundary was not justified while the existing registrars already provide a cohesive reuse point.
