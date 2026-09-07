---
id: TD-002
title: "Modular tool registration and discovery"
status: accepted
date: 2026-08-31
supersedes: null
superseded_by: null
tags: [mcp, tools, discovery]
---

# TD-002: Modular tool registration and discovery

## Context

Tool registration originally combined MCP framing, platform operations, and filesystem discovery in one large factory. Repeated timeout and error wrappers obscured each tool's behavior, while unrelated tools and path-resolution rules changed in the same class.

## Decision

`ToolScope` centralizes MCP timeout, cancellation, result, and error handling. Focused `ToolRegistrar` implementations own related tool registrations, and `ToolDiscovery` owns platform artifact and project-path resolution. `ToolFactory` composes an extensible list of registrars rather than defining a fixed registrar count.

## Rationale

This separates protocol framing, cohesive tool behavior, and environment discovery so each can evolve and be tested independently. Adding a focused registrar extends composition without restoring a monolith or forcing unrelated registrations to change.

## Consequences

- **Positive:** Timeout and error behavior is consistent across tools.
- **Positive:** Registrars and discovery rules have narrow, independently testable responsibilities.
- **Negative:** Following one tool from factory wiring to its handler crosses several small files.
- **Negative:** New registrars must be added explicitly to the factory composition.

## Alternatives Considered

### One registration factory

Rejected because it couples every tool and discovery rule to a single growing class.

### One class per tool

Rejected because most registrations are too small to justify that file-level granularity.
