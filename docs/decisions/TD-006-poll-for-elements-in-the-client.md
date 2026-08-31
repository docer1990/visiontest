---
id: TD-006
title: "Poll for elements in the client"
status: accepted
date: 2026-08-31
supersedes: null
superseded_by: null
tags: [automation, polling, synchronization]
---

# TD-006: Poll for elements in the client

## Context

Agents previously repeated single-shot element searches while waiting for UI transitions. Adding a server-side wait would require new Android and iOS artifacts and would hold a forwarded HTTP request open for the entire wait.

## Decision

Kotlin clients perform bounded polling of the existing `ui.findElement` operation at 500 ms intervals. Android and iOS expose consistent appear and gone waits: appearance returns the find result, disappearance returns confirmation, timeouts name the selectors, and transport or protocol failures remain errors.

## Rationale

Client polling works with already-installed automation servers, keeps the shared timing and response rules testable on the host, and gives both platforms the same synchronization contract without a new JSON-RPC method.

## Consequences

- **Positive:** One bounded call replaces agent-authored polling loops.
- **Positive:** Android and iOS handle absence, timeout, and server failure consistently.
- **Negative:** A 30-second wait can make many localhost JSON-RPC round trips.
- **Negative:** Detection latency is limited by the fixed 500 ms interval.

## Alternatives Considered

### Server-side wait method

Rejected because it would require new device artifacts and a long-lived request through platform transport.

### Leave polling to callers

Rejected because every agent would need to reproduce deadline and failure semantics correctly.
