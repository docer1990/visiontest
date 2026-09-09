---
id: TD-010
title: "Keep wait and tap atomic in native servers"
status: accepted
date: 2026-09-09
supersedes: null
superseded_by: null
tags: [automation, synchronization, gestures]
---

# TD-010: Keep wait and tap atomic in native servers

## Context

Scripts and agents need to tap elements that may appear only after a screen transition. Composing the existing client-side appearance wait with a coordinate tap would expose no coordinates to callers, but the element could move or stop being actionable between the two native requests.

## Decision

Android and iOS automation servers will expose `ui.tapOnElement`, which waits within a bounded timeout for the selected element to become actionable and then taps the same native element reference. Passive appearance and disappearance waits remain client-polled under TD-006.

## Rationale

Keeping readiness detection and the gesture in one native request removes the find-to-tap race and lets each platform use its real interaction semantics: visible enabled `UiObject2` on Android and enabled `isHittable` `XCUIElement` on iOS. This reliability outweighs the artifact update and long-lived-request costs for an action that must operate on the element it validated.

## Consequences

- **Positive:** Callers issue one command without sleeps, coordinate parsing, or polling loops.
- **Positive:** Moving elements and in-progress animations cannot invalidate host-computed coordinates.
- **Negative:** Android and iOS artifacts gain a new JSON-RPC method and platform-specific readiness loops.
- **Negative:** Installed iOS bundles must be updated before `ios_tap_on_element` can be used.

## Alternatives Considered

### Client wait followed by coordinate tap

Rejected because bounds can become stale and element presence does not guarantee actionability.

### Client wait followed by native element tap

Rejected because two native requests retain a race while still requiring a new native method.
