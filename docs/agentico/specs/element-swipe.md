# Element swipe

## Purpose

Swipe within a selected UI element on Android or iOS, allowing carousels and
scrollable areas to be manipulated through MCP and the CLI.

## Requirements

### Requirement: CLI and MCP share platform operations

MCP MUST expose `android_swipe_on_element` and `ios_swipe_on_element`. CLI MUST
expose `swipe_on_element -p android|ios <direction>` and delegate to the same
platform registrar operation. Android MUST use its existing `ui.swipeOnElement`
implementation; iOS MUST implement native `ui.swipeOnElement` in its bundle.

### Requirement: Input identifies an element and a gesture

Direction MUST be up, down, left, or right. Speed MUST be slow, normal, or fast,
defaulting to normal. At least one selector MUST be provided: exact text,
partial text, resource identifier, class name, or content description. iOS MUST
interpret these as text, partial text, accessibility identifier, element type,
and accessibility label respectively. Optional iOS `bundleId` scopes the app
and MUST NOT count as a selector.

#### Scenario: A bundle is supplied without an element selector

- **Given** an iOS element swipe contains only a bundle ID and direction
- **When** the request is validated
- **Then** it SHALL fail validation and SHALL NOT perform a gesture

### Requirement: iOS resolves and swipes inside one request

iOS MUST use the same selection semantics as its existing `findElement`
operation. It MUST resolve the target and perform the gesture inside the native
server request using the element's bounds and the existing speed durations.
The server MUST intersect the element bounds with the visible screen before
calculating endpoints. Endpoints MUST remain inside those usable bounds, with
margins consistent with Android.

#### Scenario: A carousel is found

- **Given** a selector matches an element with usable bounds
- **When** a left swipe is requested
- **Then** the gesture SHALL move from the right interior to the left interior
  of that element, preserving the requested speed

#### Scenario: No element matches

- **Given** no element matches the selectors
- **When** the server handles the request
- **Then** it SHALL return an operation result with `success: false` and an error
  and SHALL NOT perform a gesture

#### Scenario: Bounds cannot support a gesture

- **Given** the matched element has empty or nonfinite bounds
- **When** the server calculates endpoints
- **Then** it SHALL return an operation failure and SHALL NOT perform a gesture

### Requirement: The installed iOS bundle supports the method

Users of an older bundle MUST update or rebuild it to use this operation. The
new CLI/MCP tool does not add methods to an already installed bundle. The
existing JSON-RPC method-not-found response remains an operation response.

## Verification

- Kotlin iOS client and registrar tests cover wire mapping, defaults, validation,
  schema registration, and delegation.
- Swift tests cover request validation and bounded gesture geometry.
- `McpStdioE2ETest` verifies the public tool registration through the packaged JAR.
