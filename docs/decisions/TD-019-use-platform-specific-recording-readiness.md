---
id: TD-019
title: "Use platform-specific recording readiness"
status: accepted
date: 2026-10-22
supersedes: null
superseded_by: null
tags: [recording, android, ios, latency]
---

# TD-019: Use platform-specific recording readiness

## Context
`simctl` emits `Recording started` after processing its first frame. Android
`screenrecord` has no equivalent signal. A local static-screen probe observed
an initialized MP4 header but no visible frame payload before a ten-second timeout.
A separate recording contained a video sample after normal finalization.

## Decision
Wait for the documented first-frame signal on iOS. On Android, require a live
recorder and a structurally initialized MP4 container, including its media-data
header. Validate video samples after finalization on both platforms.

## Rationale
Waiting for Android to flush frame data would delay valid short flows on static
screens. Initialization is the startup contract on Android, not proof of a first
frame. Preserve that distinction in documentation and tests.

## Consequences
- Readiness can finish without an arbitrary fixed sleep.
- Android needs bounded startup-only file inspection and container parsing.
- A recorder can initialize and still fail to produce a valid final video.
- Local measurements support feasibility, not a full-flow performance claim.

## Alternatives Considered
Process liveness alone misses initialization errors. File existence alone accepts
an empty file. Waiting for Android frame bytes was rejected after the probe.
Periodic device checks throughout the flow would add transport overhead.
