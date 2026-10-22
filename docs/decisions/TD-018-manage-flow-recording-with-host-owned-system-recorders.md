---
id: TD-018
title: "Manage flow recording with host-owned system recorders"
status: accepted
date: 2026-10-22
supersedes: null
superseded_by: null
tags: [cli, recording, processes, artifacts]
---

# TD-018: Manage flow recording with host-owned system recorders

## Context
Flows already own sequential execution, deadlines, observations and reports.
An explicitly requested video must start before the first step and survive an
operation failure. Android and iOS Simulator provide system recording commands.

## Decision
Use host-owned sessions around Android `screenrecord` and iOS `simctl recordVideo`,
coordinated by the flow executor. Treat recording as a required result when the
caller supplies `--record`, with bounded finalization outside the flow deadline.

## Rationale
Platform adapters isolate process and file-transfer differences while the flow
retains its shared registrar operations under TD-003 and TD-016. Host artifact
paths follow the ownership principle in TD-005 without transferring video through
JSON-RPC or holding it entirely in memory.

## Consequences
- Native automation bundles need no recording endpoints or upgrades.
- The host must own process termination, cancellation and Android file transfer.
- Unexpected recorder exit or invalid final video can fail an otherwise successful flow.
- Older Android recorders can reject budgets beyond their supported duration.

## Alternatives Considered
Native recording endpoints would provide more control but require new protocols
and installed-server updates. Detached recording commands would split ownership
across invocations. Best-effort video would violate the caller's required result.
