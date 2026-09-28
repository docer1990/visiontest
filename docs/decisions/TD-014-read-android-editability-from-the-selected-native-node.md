---
id: TD-014
title: "Read Android editability from the selected native node"
status: accepted
date: 2026-09-28
supersedes: null
superseded_by: null
tags: [android, accessibility, uiautomator, interactions]
---

# TD-014: Read Android editability from the selected native node

## Context

Targeted Android input must reject a selected element before tapping it when the
element is not editable. UIAutomator 2.3.0 exposes the selected `UiObject2`, but
its public API omits the underlying node's `isEditable` property. Class-name
allowlists reject valid custom editable controls and cannot satisfy the contract.

## Decision

Read `isEditable` from the `AccessibilityNodeInfo` owned by the selected
`UiObject2` through the pinned UIAutomator 2.3.0 private accessor.

## Rationale

The selected native element remains the source of truth for both readiness and
the tap. This avoids a second accessibility-tree lookup that can select a
different matching element or race a UI update. The project already pins the
UIAutomator version, so tests can detect an incompatible library change.

## Consequences

- **Positive:** Custom editable controls pass readiness without class-name rules.
- **Positive:** Noneditable controls remain blocked before any tap.
- **Negative:** A UIAutomator upgrade can require adapting the private accessor.
- **Negative:** Access failure becomes an explicit native operation failure.

## Alternatives Considered

### Traverse the accessibility tree separately

Rejected because a second selector implementation can diverge from UIAutomator
and return a different element.

### Expand the class-name allowlist

Rejected because no finite allowlist covers custom editable controls.
