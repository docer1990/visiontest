---
id: TD-008
title: "Structured CLI output and native iOS element swipe"
status: accepted
date: 2026-09-07
supersedes: null
superseded_by: null
tags: [cli, json, ios]
---

# TD-008: Structured CLI output and native iOS element swipe

## Context
P3 exposes existing operations through the CLI and adds machine-readable inspection.
Device registrars currently format app and device metadata as prose. iOS supports
finding an element and swiping coordinates, but has no element swipe operation.

## Decision
Generate text and JSON from shared backend data, retaining the registrar reuse
boundary established by TD-003. Resolve and swipe iOS elements in a single native
server request, exposed through the same registrar to CLI and MCP.

## Rationale
Scripts need named fields, not prose to parse. Native lookup and gesture avoid an
extra client round trip during which the target can change. This follows Android's
existing operation boundary and allows native element bounds to guide the gesture.

## Consequences
- JSON schemas and text compatibility require explicit tests and documentation.
- iOS element swipe requires an updated automation bundle and Swift validation.
- No new dependencies or core module are required.

## Alternatives Considered
### Encode formatted text as a JSON string
Rejected because callers still need to parse prose to use the data.
### Compose find and swipe in Kotlin
Rejected because lookup and gesture require separate requests and stale coordinates
are more likely. The native operation can inspect bounds immediately before swiping.
