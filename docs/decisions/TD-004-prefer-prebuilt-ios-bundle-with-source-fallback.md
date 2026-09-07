---
id: TD-004
title: "Prefer prebuilt iOS bundle with source fallback"
status: accepted
date: 2026-08-31
supersedes: null
superseded_by: null
tags: [ios, distribution, xctest]
---

# TD-004: Prefer prebuilt iOS bundle with source fallback

## Context

Starting the iOS automation server from an Xcode project compiles the test target and requires the VisionTest source tree. Installed users instead receive a pre-built XCTest bundle, while contributors and unsupported environments may still need a source build.

## Decision

Prefer an installed pre-built `.xctestrun` bundle and launch it with `xcodebuild test-without-building`. Use the discovered Xcode source project as a compatibility fallback when no bundle exists or the pre-built launch exits early.

## Rationale

The installed artifact gives users a faster, source-independent startup path without removing a recovery path for bundle incompatibility or development builds. Keeping both paths behind the same start handler preserves the external MCP and CLI behavior.

## Consequences

- **Positive:** Normal installed use avoids rebuilding the iOS test target.
- **Positive:** Source checkouts remain usable when the distributed bundle is absent or incompatible.
- **Negative:** Installation must ship a bundle compatible with the user's simulator toolchain.
- **Negative:** Startup and diagnostics must distinguish pre-built, source, and fallback attempts.

## Alternatives Considered

### Always build from source

Rejected because it requires the repository and adds avoidable startup time for installed users.

### Support only the pre-built bundle

Rejected because Xcode compatibility and contributor workflows still require a source escape hatch.
