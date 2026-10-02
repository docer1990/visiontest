---
id: TD-012
title: "Use one command per gesture with explicit targeting"
status: accepted
date: 2026-09-10
supersedes: null
superseded_by: null
tags: [automation, cli, mcp, gestures]
---

# TD-012: Use one command per gesture with explicit targeting

## Context

Long press and double tap must work with either screen coordinates or element selectors. Separate coordinate and element commands would make each schema simple, but would double the new CLI and MCP entries. A generic gesture command would reduce the command count but weaken help text and validation for each gesture.

## Decision

Expose one long-press command and one double-tap command per facade and platform. Each command accepts exactly one target form: a coordinate pair or one or more element selectors.

## Rationale

One command per gesture keeps each user intent discoverable without duplicating command names. Explicit validation preserves clear errors despite the two accepted input forms. Native servers resolve selector targets and perform gestures in one request, consistent with TD-010.

## Consequences

- **Positive:** CLI and MCP listings remain compact, and each gesture has focused help text.
- **Positive:** Callers may move between coordinate and selector targeting without choosing another command.
- **Negative:** Runtime validation must reject missing, partial, and mixed target forms.
- **Negative:** Schemas cannot express every mutually exclusive combination to all MCP clients.

## Alternatives considered

### Separate commands for coordinates and elements

Rejected because it doubles the public entries for two gestures and repeats registrar logic.

### One generic gesture command

Rejected because an action parameter makes discovery, schemas, and gesture-specific validation less clear.
