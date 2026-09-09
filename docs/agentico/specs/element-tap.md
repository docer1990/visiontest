# Element tap

## Purpose

Tap an actionable UI element directly from stable selectors on Android or iOS,
without first converting an inspection result into screen coordinates.

## Requirements

### Requirement: Element taps are available through MCP and the CLI

MCP MUST expose `tap_on_element` on Android and `ios_tap_on_element` on iOS.
The CLI MUST expose `tap_on_element -p android|ios` and delegate to the same
platform registrar operations.

### Requirement: Element selection and app scope are explicit

An element tap MUST require at least one of exact text, partial text, resource
identifier, class name, or content description. iOS MUST interpret these as
text, partial text, accessibility identifier, element type, and accessibility
label respectively. Optional iOS `bundleId`/`--bundle-id` scopes the target app
but MUST NOT count as a selector. Android MUST reject an app scope, including a
blank one, as invalid input; a blank iOS app scope is also invalid.

#### Scenario: Bundle ID without a selector

- **Given** an iOS element tap contains only a bundle ID
- **When** the request is validated
- **Then** it SHALL fail validation and SHALL NOT tap

#### Scenario: Android receives app scope

- **Given** an Android CLI element tap contains `--bundle-id`
- **When** the command validates its arguments
- **Then** it SHALL exit 2 before contacting the backend

### Requirement: Element taps wait briefly for a match

Native Android and iOS handling MUST wait implicitly for a matching element at
500 ms intervals. The caller timeout defaults to 10,000 ms and MUST NOT exceed
30,000 ms. Element tapping MUST NOT auto-scroll to make a match visible.

#### Scenario: Match arrives during the implicit wait

- **Given** no matching element is present initially
- **When** a matching actionable element appears before the caller timeout
- **Then** the native server SHALL tap it directly

#### Scenario: Match never arrives

- **Given** no selector match appears before the caller timeout
- **When** the implicit wait expires
- **Then** the operation SHALL report an absent-element timeout and SHALL NOT tap

### Requirement: A direct tap requires platform actionability

Android MUST directly click the matched element only when it is visible and
enabled. iOS MUST directly tap the matched element only when it exists, is
enabled, and is hittable. Neither platform may replace this action with a
coordinate tap.

#### Scenario: Matched element is blocked

- **Given** a selector matches an element that is not actionable for its platform
- **When** the native server evaluates it
- **Then** it SHALL report a blocked-element timeout or failure and SHALL NOT tap

### Requirement: Element-tap outcomes remain distinguishable

The operation result MUST distinguish an absent-element timeout, a blocked
element timeout, a successful direct tap, and an underlying native or transport
error. An operation failure MUST never report that a tap occurred.

### Requirement: Installed native servers must support the method

`ui.tapOnElement` is a new native JSON-RPC method. Users MUST update the Android
automation APK pair and the iOS automation bundle before using it; updating only
the CLI/MCP JAR does not add the method to installed native servers. A
method-not-found response from an older server remains an operation error.

## Verification

- Kotlin client, registrar, and CLI tests cover selector mapping, app scope,
  timeouts, validation, and public registration.
- Android and iOS native tests cover implicit waiting, actionability, direct
  interaction, and no-tap failure paths.
- `McpStdioE2ETest` verifies the Android and iOS MCP tool names.
