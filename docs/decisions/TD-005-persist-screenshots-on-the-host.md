---
id: TD-005
title: "Persist screenshots on the host"
status: accepted
date: 2026-08-31
supersedes: null
superseded_by: null
tags: [screenshots, json-rpc, filesystem]
---

# TD-005: Persist screenshots on the host

## Context

Android and iOS automation servers capture PNG bytes inside a device or simulator process, while callers need a stable path in the host project's filesystem. Existing automation transport is JSON-RPC, and device-side paths do not share reliable semantics with host paths.

## Decision

Automation servers return the PNG as base64 in the JSON-RPC result. The Kotlin host resolves absolute or project-relative output paths, creates missing parent directories, decodes the payload, and writes through a sibling temporary file before atomically replacing the destination when supported.

## Rationale

Base64 keeps screenshots on the established JSON-RPC transport, while host persistence makes caller-supplied paths unambiguous across both platforms. A shared saver also gives Android and iOS the same validation and partial-write protection.

## Consequences

- **Positive:** Screenshots land where the MCP or CLI caller expects on the host.
- **Positive:** Directory creation and atomic replacement prevent common persistence failures and partial PNGs.
- **Negative:** Base64 increases payload size and requires a full in-memory decode.
- **Negative:** Explicit paths may replace existing files under the caller's permissions.

## Alternatives Considered

### Write from the automation server

Rejected because simulator and device filesystem paths do not reliably identify host destinations.

### Add a binary HTTP endpoint

Rejected because typical screenshot sizes do not justify a second transport surface.
