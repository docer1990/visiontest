---
id: TD-013
title: "Resolve the active iOS app through XCTest accessibility"
status: accepted
date: 2026-09-15
supersedes: null
superseded_by: null
tags: [ios, xcuitest, accessibility, compatibility]
---

# TD-013: Resolve the active iOS app through XCTest accessibility

## Context

The iOS automation server runs without a fixed application under test. Public `XCUIApplication` initializers require a bundle identifier or the scheme's target application. A SpringBoard proxy cannot query controls owned by another foreground app. The interaction contract permits callers to omit `bundleId` and then targets the active app.

## Decision

Use XCTest's runtime accessibility interface to resolve the active application process. Keep explicit bundle identifiers as the preferred path and fall back to a cached foreground app, then SpringBoard, if XCTest changes the private selectors.

## Rationale

The native server must perform unscoped keyboard, alert, and targeted-input operations in one request. The accessibility interface is already present in the supported Xcode runtime and returns the processes that XCTest considers active. Runtime selector checks preserve compatibility when an Xcode release removes or renames the private methods.

## Consequences

- **Positive:** Unscoped interactions reach the foreground app without requiring client-side process state.
- **Negative:** Xcode upgrades can disable automatic app discovery until VisionTest adapts to XCTest changes.

## Alternatives considered

### Require `bundleId`

This would remove the accepted optional-scope behavior.

### Query SpringBoard

SpringBoard exposes system UI but not the foreground app's element tree.

### Track launched apps in the Kotlin process

Separate CLI invocations do not share that state, and apps can enter the foreground outside VisionTest.
