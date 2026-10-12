---
id: TD-015
title: "Isolate local performance measurements from operation results"
status: proposed
date: 2026-10-05
supersedes: null
superseded_by: null
tags: [performance, tracing, benchmarks, compatibility]
---

# TD-015: Isolate local performance measurements from operation results

## Context

Issue #57 requires correlated CLI, MCP, and native timings without changing
operation results. Issue #60 also needs complete-flow and agent usage measurements.
CLI processes exit after each command; MCP serves concurrent requests in one process.

## Decision

Write opt-in host traces to local JSONL files and transport bounded native timing
metadata in optional HTTP headers. Use external benchmark processes for launcher
timing and separate agent adapters for observed usage and flow outcomes.

The design direction is approved. Keep this decision proposed until implementation
and compatibility verification establish the contracts below.

## Rationale

Separate metadata preserves the shared operation boundary in TD-003 and public
JSON-RPC bodies. External timing includes launcher and JVM costs unavailable to
application timers. Agent adapters expose accounting gaps instead of presenting
payload size as actual model usage.

## Consequences

- Existing servers can omit timing headers and still execute operations once.
- Trace writers need bounded buffering, explicit completeness, and failure isolation.
- Native SDK internals can remain unmeasured; reports must name those gaps.
- Agent usage availability depends on the selected client's observable events.

## Alternatives Considered

### Add timing fields to operation results

Rejected because timing would enter public CLI/MCP payloads and consume agent context.

### Retrieve native timings with a second RPC

Rejected because it adds requests, retention, and correlation races to every sample.

### Use only host timers or payload token estimates

Rejected because neither explains native execution stages or actual agent usage.
