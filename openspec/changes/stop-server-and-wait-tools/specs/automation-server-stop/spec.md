## ADDED Requirements

### Requirement: Android automation server can be stopped
The system SHALL provide a `stop_automation_server` MCP tool for Android that terminates the on-device automation server (instrumentation and app processes via `am force-stop`) and removes the ADB port forward for the automation port.

#### Scenario: Stop a running server
- **WHEN** `stop_automation_server` is invoked while the Android automation server is running
- **THEN** the instrumentation and app processes are force-stopped, the `tcp:9008` forward is removed, and the tool reports success after verifying the server no longer responds to `/health`

#### Scenario: Stop is idempotent
- **WHEN** `stop_automation_server` is invoked while no automation server is running
- **THEN** the tool succeeds with a message stating the server was not running (forward removal failures are tolerated silently)

#### Scenario: adb unreachable
- **WHEN** `stop_automation_server` is invoked and adb cannot reach any device
- **THEN** the tool returns a structured error naming the adb failure, not a stack trace

### Requirement: CLI stop subcommand for both platforms
The CLI SHALL provide a `stop_automation_server` subcommand accepting `--platform android|ios` that invokes the same stop logic as the corresponding MCP tools (including the existing iOS xcodebuild-process teardown).

#### Scenario: CLI stops the Android server
- **WHEN** `visiontest stop_automation_server -p android` runs while the server is running
- **THEN** the server is stopped and the command exits with code 0

#### Scenario: CLI stop is idempotent
- **WHEN** `visiontest stop_automation_server -p ios` runs and no iOS automation server is active
- **THEN** the command prints that nothing was running and exits with code 0

#### Scenario: Toolchain unreachable
- **WHEN** `visiontest stop_automation_server -p android` runs and adb is not available
- **THEN** the command exits with code 3

### Requirement: Stop tool is part of the MCP contract
The `stop_automation_server` tool SHALL be listed in `McpStdioE2ETest.EXPECTED_TOOLS` and documented in the CLAUDE.md tool and CLI tables.

#### Scenario: Contract test covers the new tool
- **WHEN** the MCP stdio E2E test runs `tools/list`
- **THEN** `stop_automation_server` is present exactly once, with a non-empty description and an input schema
