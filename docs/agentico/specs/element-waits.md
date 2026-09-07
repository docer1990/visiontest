# Element waits

## Purpose

Element waits provide bounded client-side polling for an element to appear or disappear while preserving selector mapping and failure semantics across Android and iOS.

## Requirements

### Requirement: Waits use the platform find-element selectors

Android waits MUST accept at least one of `text`, `textContains`, `resourceId`, `className`, or `contentDescription`. iOS waits MUST accept the same tool-facing selector names and MAY additionally accept `bundleId` to scope the target application, but `bundleId` alone MUST NOT satisfy the selector requirement.

#### Scenario: Android selectors are forwarded

- **Given** one or more Android selectors are present
- **When** a wait polls
- **Then** each poll SHALL call Android `ui.findElement` with those selector values

#### Scenario: iOS selectors are mapped

- **Given** iOS `resourceId`, `className`, `contentDescription`, and optional `bundleId` values
- **When** a wait polls
- **Then** the iOS client SHALL map them to its identifier, element type, label, and app scope while preserving the tool-facing JSON-RPC parameter names

#### Scenario: No actual selector

- **Given** all five element selectors are absent, including when iOS has only `bundleId`
- **When** a wait begins after its server health check
- **Then** it MUST fail with an invalid-argument message naming the required selectors

### Requirement: Wait timing is bounded consistently

Both platforms MUST poll at 500 ms intervals, MUST default to a 10,000 ms caller timeout, and MUST accept explicit timeouts only from 1 through 30,000 ms inclusive. Their MCP tool wrapper timeout MUST be 35,000 ms so the wait's own deadline can report first.

#### Scenario: Default timeout

- **Given** no timeout is supplied
- **When** either platform starts an appearance or disappearance wait
- **Then** it SHALL use a 10,000 ms wait budget

#### Scenario: Timeout is outside bounds

- **Given** an explicit timeout is zero, negative, or greater than 30,000 ms
- **When** the registrar validates it
- **Then** it MUST fail as an invalid argument before polling `ui.findElement`

### Requirement: Appearance and disappearance have distinct success results

An appearance wait MUST return the raw successful `ui.findElement` JSON-RPC response as soon as `result.found` is true. A disappearance wait MUST return confirmation text naming the selectors as soon as `result.found` is false. Each wait MUST throw a timeout naming the selectors and configured budget when its expected state is not reached.

#### Scenario: Element appears

- **Given** earlier polls return `found: false`
- **When** a poll returns a result object with boolean `found: true`
- **Then** the appearance wait SHALL immediately return that raw response

#### Scenario: Element disappears

- **Given** earlier polls return `found: true`
- **When** a poll returns a result object with boolean `found: false`
- **Then** the disappearance wait SHALL return that the element is no longer present

#### Scenario: Deadline is reached

- **Given** every valid response continues to report the undesired state
- **When** another poll interval would exceed the configured budget
- **Then** the wait SHALL throw a timeout describing `not found` or `still present` and the selectors

### Requirement: Server and protocol failures never satisfy a gone wait

Each platform registrar MUST require a successful health check before validation and polling. During polling, an HTTP failure, transport disconnect, JSON-RPC `error`, malformed JSON, missing object `result`, missing `found`, or non-boolean `found` MUST propagate as an error and MUST NOT be interpreted as absence.

#### Scenario: Server is unavailable initially

- **Given** the automation server health check fails
- **When** any wait operation is invoked
- **Then** it SHALL fail as server-not-running before making a find request; the CLI SHALL map this to exit code 3

#### Scenario: Server dies during a gone wait

- **Given** an element was present on an earlier poll
- **When** a later `ui.findElement` request fails at transport or server level
- **Then** the gone wait MUST fail rather than report that the element disappeared

#### Scenario: Malformed JSON-RPC result

- **Given** a response lacks an object result with a boolean `found`
- **When** either wait parses it
- **Then** it MUST throw a command-execution failure containing protocol context

### Requirement: Wait operations are available through both facades

MCP MUST expose `wait_for_element` and `wait_until_gone` for Android and `ios_wait_for_element` and `ios_wait_until_gone` for iOS. CLI MUST expose `wait_for_element`, using `--gone` to select disappearance and `--timeout` for the timeout in milliseconds.

#### Scenario: CLI wait outcome mapping

- **Given** a valid CLI wait invocation
- **When** its handler succeeds, times out, receives invalid input, or finds the server stopped
- **Then** it SHALL respectively exit 0, 1, 2, or 3 under the shared CLI exception mapping

## Verification

- Production polling and configuration: `app/src/main/kotlin/com/example/visiontest/common/JsonRpcHttpClient.kt`, `app/src/main/kotlin/com/example/visiontest/config/AutomationConfig.kt`, `app/src/main/kotlin/com/example/visiontest/config/IOSAutomationConfig.kt`
- Production selectors and facades: `app/src/main/kotlin/com/example/visiontest/android/AndroidElementSelectors.kt`, `app/src/main/kotlin/com/example/visiontest/ios/IOSElementSelectors.kt`, `app/src/main/kotlin/com/example/visiontest/tools/AndroidWaitToolRegistrar.kt`, `app/src/main/kotlin/com/example/visiontest/tools/IOSWaitToolRegistrar.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/WaitForElementCommand.kt`
- Executable tests: `app/src/test/kotlin/com/example/visiontest/android/AutomationClientWaitTest.kt`, `app/src/test/kotlin/com/example/visiontest/tools/AndroidWaitToolRegistrarTest.kt`, `app/src/test/kotlin/com/example/visiontest/tools/IOSWaitToolRegistrarTest.kt`, `app/src/test/kotlin/com/example/visiontest/cli/CliCommandIntegrationTest.kt`, `app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt`
