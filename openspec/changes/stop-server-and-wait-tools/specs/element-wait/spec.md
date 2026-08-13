## ADDED Requirements

### Requirement: Wait for element to appear
The system SHALL provide `wait_for_element` (Android) and `ios_wait_for_element` (iOS) MCP tools that accept the same selectors as the platform's `find_element` tool plus an optional `timeoutMs` (default 10000, maximum 30000), polling the running automation server until the element is found or the timeout elapses.

#### Scenario: Element appears within the timeout
- **WHEN** `wait_for_element` is invoked with a selector matching an element that appears after 2 seconds
- **THEN** the tool returns the element payload in the same shape as `find_element` as soon as a poll finds it

#### Scenario: Timeout elapses
- **WHEN** `wait_for_element` is invoked with a selector that never matches within `timeoutMs`
- **THEN** the tool returns a structured timeout message naming the selectors and elapsed time (not an exception dump)

#### Scenario: No selector provided
- **WHEN** `wait_for_element` is invoked with no selector arguments
- **THEN** the tool returns a usage error identical in style to `find_element`'s selector validation

#### Scenario: Timeout above the maximum
- **WHEN** `wait_for_element` is invoked with `timeoutMs` greater than 30000
- **THEN** the tool rejects the request with a validation error naming the maximum

### Requirement: Wait for element to disappear
The system SHALL provide `wait_until_gone` (Android) and `ios_wait_until_gone` (iOS) MCP tools with the same selector and timeout parameters, succeeding when the element is no longer found.

#### Scenario: Element disappears
- **WHEN** `wait_until_gone` is invoked for an element that disappears after 1 second
- **THEN** the tool returns success as soon as a poll reports the element not found

#### Scenario: Server failure is not "gone"
- **WHEN** the automation server stops responding during a `wait_until_gone` poll
- **THEN** the tool fails with a server-unreachable error rather than reporting the element gone

#### Scenario: Malformed response is not "gone"
- **WHEN** a poll returns a result object whose `found` field is missing or not a boolean
- **THEN** the tool fails with a protocol error rather than treating the element as absent

### Requirement: Waits require a running automation server
Wait tools SHALL fail fast with the platform's standard server-not-running error when invoked without a running automation server.

#### Scenario: Server not started
- **WHEN** `wait_for_element` is invoked and the automation server is not running
- **THEN** the tool returns the same "start the automation server first" error as other automation tools

### Requirement: CLI wait subcommand
The CLI SHALL provide a `wait_for_element` subcommand accepting `--platform android|ios`, the selector options, `--timeout MS`, and a `--gone` flag that flips to disappearance mode.

#### Scenario: Successful CLI wait
- **WHEN** `visiontest wait_for_element -p android --text "Login" --timeout 5000` runs and the element appears
- **THEN** the element payload is printed and the command exits with code 0

#### Scenario: CLI timeout
- **WHEN** the element does not appear within `--timeout`
- **THEN** the command exits with code 1

#### Scenario: Invalid arguments
- **WHEN** no selector is given, or `--timeout` exceeds 30000
- **THEN** the command exits with code 2

### Requirement: Wait tools are part of the MCP contract
The four wait tools SHALL be listed in `McpStdioE2ETest.EXPECTED_TOOLS`, registered with a Tool DSL timeout exceeding the maximum wait budget, and documented in the CLAUDE.md tool and CLI tables.

#### Scenario: Contract test covers the wait tools
- **WHEN** the MCP stdio E2E test runs `tools/list`
- **THEN** `wait_for_element`, `wait_until_gone`, `ios_wait_for_element`, and `ios_wait_until_gone` are each present exactly once with descriptions and input schemas
