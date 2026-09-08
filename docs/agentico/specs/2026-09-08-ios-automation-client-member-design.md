# iOS Automation Client Member Design

## Context

`IOSAutomationClient.swipeOnElement` must be callable as a class member, like every other operation exposed by the client. A top-level extension makes consumers outside the `ios` package import the operation separately and creates an inconsistent public API.

## Design

Move `swipeOnElement` into `IOSAutomationClient` without changing its signature or JSON-RPC payload. Remove the extension import from `IOSAutomationToolRegistrar`. Record a targeted `TooManyFunctions` entry for `IOSAutomationClient` in the existing Detekt baseline, matching the established treatment of the Android `AutomationClient`; the global threshold remains unchanged.

## Verification

A test in a consumer package must compile and invoke `client.swipeOnElement(...)` without importing an extension. Existing request-serialization, registrar, CLI parity, and full Gradle build checks must pass.

## Follow-up

Assess whether Android and iOS clients contain separable responsibilities with stable boundaries. Track a multi-client refactor only if it reduces class responsibilities while preserving a coherent API for registrars and tests.
