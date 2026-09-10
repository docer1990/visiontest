# Command-line interface

## Purpose

The command-line interface provides scriptable access to selected VisionTest operations while preserving the no-argument MCP stdio entry point used by agent hosts.

## Requirements

### Requirement: The entry point routes MCP and CLI modes by the first argument

The JAR MUST start the MCP stdio server when invoked with no arguments or when its first argument is exactly `serve`. Every other first argument MUST enter the CLI parser.

#### Scenario: No arguments

- **Given** the VisionTest JAR is started without arguments
- **When** the entry point routes the invocation
- **Then** it SHALL register all MCP tools and connect the server to standard input and standard output

#### Scenario: Serve sentinel

- **Given** the first argument is `serve`
- **When** the entry point routes the invocation
- **Then** it SHALL start MCP mode and SHALL NOT parse `serve` or any remaining arguments as CLI options

#### Scenario: Help or a subcommand

- **Given** the first argument is not `serve`
- **When** the entry point routes the invocation
- **Then** it SHALL construct the CLI root command without starting MCP mode

### Requirement: The CLI exposes the current command set

The CLI SHALL register these 23 subcommands and argument contracts:

| Subcommand | Platform contract | Operation-specific inputs |
|---|---|---|
| `install_automation_server` | Android only | None |
| `start_automation_server` | Android or iOS | None |
| `stop_automation_server` | Android or iOS | None |
| `automation_server_status` | Android or iOS | None |
| `get_interactive_elements` | Android or iOS | Optional `--include-disabled`, iOS app scope `--bundle-id`, `--json` |
| `get_ui_hierarchy` | Android or iOS | Optional iOS app scope `--bundle-id` |
| `get_device_info` | Android or iOS | Optional `--json` |
| `find_element` | Android or iOS | At least one selector; optional `--bundle-id` on iOS and `--json` |
| `available_device` | Android or iOS | Optional `--json` |
| `screenshot` | Android or iOS | Optional `--output PATH` |
| `wait_for_element` | Android or iOS | One or more selector options; optional iOS app scope `--bundle-id`, plus `--timeout MS` and `--gone` |
| `tap_by_coordinates` | Android or iOS | Required integer `x` and `y` arguments |
| `tap_on_element` | Android or iOS | At least one selector; optional iOS app scope `--bundle-id` and `--timeout MS` |
| `input_text` | Android or iOS | Required `text` argument; optional iOS app scope `--bundle-id` |
| `swipe_direction` | Android or iOS | Required `up`, `down`, `left`, or `right`; optional `--distance` and `--speed` choices |
| `swipe` | Android or iOS | Integer `startX`, `startY`, `endX`, `endY`; optional positive `--steps` (default 20) |
| `swipe_on_element` | Android or iOS | Direction and at least one selector; optional `--speed` and iOS `--bundle-id` |
| `press_back` | Android only | None |
| `press_home` | Android or iOS | None |
| `launch_app` | Android or iOS | Required package or bundle `id` argument |
| `list_apps` | Android or iOS | Optional `--json` |
| `info_app` | Android or iOS | Required app `id`; optional `--json` |
| `init` | No device platform | Required comma-separated `--agent` option |

#### Scenario: CLI help enumerates subcommands

- **Given** the root command is constructed
- **When** its registered subcommands are inspected
- **Then** their names MUST equal the table above without duplicate registrations

### Requirement: Device commands require an explicit supported platform

Every subcommand except `init` MUST require `--platform` or `-p` with exactly `android` or `ios`; there MUST be no default or platform auto-detection. Root `--help` and `--version` MUST also work without a platform. Android-only commands MUST parse `ios` and reject it as unsupported at execution time so the process can use exit code 5.

#### Scenario: Platform is missing or invalid

- **Given** a device subcommand has no platform or specifies a value other than `android` or `ios`
- **When** Clikt parses the invocation
- **Then** it SHALL report a usage error and exit with code 2

#### Scenario: Android-only command receives iOS

- **Given** `install_automation_server` or `press_back` specifies `--platform ios`
- **When** the command executes
- **Then** it SHALL print an Android-only error to stderr and exit with code 5

#### Scenario: Non-device entry points

- **Given** the caller requests root help, version output, or `init --agent ...`
- **When** the CLI parses the invocation
- **Then** it MUST NOT require `--platform`

### Requirement: Exit codes and output channels are stable

The CLI MUST use exit code 0 for a normally returned result, 1 for an unexpected or generic failure, 2 for usage and invalid-argument failures, 3 when an automation server is not reachable, 4 when no device or simulator is available, and 5 for an unsupported command/platform combination. Normal return strings, help, and version output MUST go to stdout; mapped failures and parse errors MUST go to stderr.

#### Scenario: Handler returns text

- **Given** a shared handler returns a string without throwing
- **When** the CLI gateway receives it
- **Then** it SHALL print that string to stdout and exit 0, even if the returned prose describes an operation-level failure

#### Scenario: Handler throws a mapped failure

- **Given** a command throws a server-not-running, no-device, no-simulator, usage, unsupported-platform, or unexpected exception
- **When** the CLI gateway handles it
- **Then** it SHALL print the exception message to stderr and use the corresponding code from 1 through 5

#### Scenario: Help and version

- **Given** a caller invokes `--help` or `--version`
- **When** Clikt emits informational output
- **Then** the entry point SHALL print it to stdout and use the status supplied by Clikt

### Requirement: CLI and MCP facades share device-operation handlers

Each device CLI command MUST delegate to the same registrar's internal suspend operation used by its MCP registration. The CLI MUST adapt typed command arguments and platform-specific names without duplicating ADB, XCUITest, JSON-RPC, polling, screenshot, or stop logic.

#### Scenario: Cross-facade delegation

- **Given** an operation is available through both CLI and MCP
- **When** either facade invokes it with equivalent inputs
- **Then** both paths SHALL call the same registrar operation and preserve its returned text or thrown failure semantics

#### Scenario: Wait command adapts two MCP operations

- **Given** the CLI `wait_for_element` command is called with or without `--gone`
- **When** it dispatches by platform
- **Then** it SHALL invoke the platform wait registrar's appearance or disappearance handler corresponding to `wait_for_element`/`wait_until_gone` on Android or `ios_wait_for_element`/`ios_wait_until_gone` on iOS

### Requirement: New selector commands validate before contacting the backend

`find_element`, `swipe_on_element`, and `tap_on_element` MUST require at least
one of `--text`, `--text-contains`, `--resource-id`, `--class-name`,
`--content-description`.
iOS maps these to text, partial text, identifier, element type, and label.
`--bundle-id` MUST scope only iOS and MUST NOT satisfy the selector requirement.
Android use of this flag MUST exit 2. Invalid direction/speed and nonpositive
coordinate swipe steps MUST exit 2 before backend access. `tap_on_element`
timeouts MUST be from 1 through 30,000 ms inclusive before backend access.

### Requirement: Element taps retain text output

`tap_on_element` MUST NOT add `--json` or any new structured-output contract.
It SHALL preserve the normal text result and mapped failure behavior.

### Requirement: Inspection offers machine-readable output

`get_interactive_elements`, `get_device_info`, `find_element`, `list_apps`,
`info_app`, and `available_device` MUST accept `--json`. Stdout MUST contain
exactly one JSON object followed by a newline, conforming to
[the documented schemas](../../cli-json.md). UI JSON-RPC framing and transport
IDs MUST be omitted. Default text output MUST remain compatible.

Operation-level failures returned normally MUST retain exit 0; scripts MUST
inspect result fields. Thrown failures MUST retain their mapped exit code and
stderr channel. Malformed JSON or invalid response structure MUST exit 1 without
emitting a partial JSON value. Before printing UI inspection JSON, the shared
formatter MUST validate the command's required fields and platform-specific
fields against the documented schema, including when `success` or `found` is
false. Optional fields MAY be absent, but MUST have their documented type when
present. Interactive `elements` MUST be an array of objects whose documented
properties have the specified types when present. Typed inspection fields MUST
NOT accept null or coerce strings into booleans or numbers. The CLI MUST accept integer fields only
in the signed 32-bit range. Unknown additive
fields MUST remain accepted and preserved.

Valid returned JSON-RPC errors MUST emit one object of the form
`{"error":{"code":-32601,"message":"Unknown method"}}` and retain exit 0.
The nested `code` MUST be a signed 32-bit integer and `message` a string. Optional `data`
MAY contain any JSON value; additional error fields MUST be preserved. The CLI
MUST omit transport framing. Invalid error objects or an error alongside a
`result` member MUST fail with exit 1, stderr diagnostics, and empty stdout.
Schema validation and RPC error handling MUST remain shared by the adapters.

#### Scenario: An element is absent

- **Given** the automation server returns an element result with `found: false`
- **When** `find_element --json` handles it
- **Then** stdout SHALL contain that result object and exit code SHALL be 0

#### Scenario: Inspection result violates its schema

- **Given** an inspection response has a missing required field, a wrong scalar type, or an invalid array or object shape
- **When** the command handles it with `--json` on either platform
- **Then** it SHALL exit 1, print a diagnostic to stderr, and leave stdout empty

#### Scenario: Inspection result has optional and additive fields

- **Given** an inspection result has every required field with the documented type and only correctly typed documented optional fields
- **When** optional fields are absent or unknown additional fields are present
- **Then** the command SHALL emit the complete result object and exit 0

#### Scenario: The backend returns a valid RPC error

- **Given** an inspection response contains an error object with an integer code and string message and no result member
- **When** the command handles it with `--json`
- **Then** stdout SHALL contain `{"error":{...}}` with the complete error object and no transport framing, stderr SHALL remain empty, and exit code SHALL be 0

#### Scenario: Installed app list is empty

- **Given** the backend returns no installed apps
- **When** `list_apps --json` handles it
- **Then** stdout SHALL be `{"apps":[]}` and exit code SHALL be 0

## Verification

- Production routing and command registration: `app/src/main/kotlin/com/example/visiontest/Main.kt`, `app/src/main/kotlin/com/example/visiontest/cli/VisionTestCli.kt`, `app/src/main/kotlin/com/example/visiontest/cli/PlatformOption.kt`
- Production result and dependency wiring: `app/src/main/kotlin/com/example/visiontest/cli/CliErrorHandler.kt`, `app/src/main/kotlin/com/example/visiontest/cli/CliExit.kt`, `app/src/main/kotlin/com/example/visiontest/cli/ComponentHolder.kt`
- Production command adapters: `app/src/main/kotlin/com/example/visiontest/cli/commands/InstallAutomationServerCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/StartAutomationServerCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/StopAutomationServerCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/AutomationServerStatusCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/GetInteractiveElementsCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/GetUiHierarchyCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/GetDeviceInfoCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/ScreenshotCommand.kt`
- Production command adapters continued: `app/src/main/kotlin/com/example/visiontest/cli/commands/WaitForElementCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/TapByCoordinatesCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/InputTextCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/SwipeDirectionCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/PressBackCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/PressHomeCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/LaunchAppCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/InitCommand.kt`
- P3 adapters and JSON output: `app/src/main/kotlin/com/example/visiontest/cli/commands/FindElementCommand.kt`, `SwipeCommand.kt`, `SwipeOnElementCommand.kt`, `ListAppsCommand.kt`, `InfoAppCommand.kt`, `AvailableDeviceCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/InspectionOutput.kt`
- Executable tests: `app/src/test/kotlin/com/example/visiontest/MainDispatchTest.kt`, `app/src/test/kotlin/com/example/visiontest/cli/VisionTestCliTest.kt`, `app/src/test/kotlin/com/example/visiontest/cli/CliErrorHandlerTest.kt`, `app/src/test/kotlin/com/example/visiontest/cli/CliCommandIntegrationTest.kt`, `app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt`
