# Automation server lifecycle

## Purpose

This capability provides explicit, scriptable teardown of the Android instrumentation server and the iOS `xcodebuild` process, with platform-specific cleanup and idempotent repeated calls.

## Requirements

### Requirement: Android stop force-stops both packages and removes the host forward

Android stop MUST health-check whether the server was running, issue `am force-stop` for `com.example.automationserver.test` and then `com.example.automationserver`, and request removal of the host endpoint `tcp:9008` with `adb forward --remove`.

#### Scenario: Running Android server stops

- **Given** the Android health endpoint initially responds
- **When** `stop_automation_server` runs
- **Then** both packages SHALL be force-stopped, the `tcp:9008` forward SHALL be removed, and shutdown verification SHALL begin

#### Scenario: Android server was already stopped

- **Given** the initial Android health check does not respond successfully
- **When** stop runs
- **Then** it SHALL still issue both force-stop commands and attempt forward cleanup before returning an informational success result

### Requirement: Android forward cleanup verifies ambiguous removal failures

If forward removal throws a command-execution failure, the registrar MUST run `adb forward --list`. It MAY tolerate the original failure only when no listed line has `tcp:9008` as its local endpoint. If that endpoint remains, or listing forwards also fails, the original removal failure MUST propagate.

#### Scenario: Removal reports missing listener

- **Given** `adb forward --remove tcp:9008` fails and `adb forward --list` contains no local `tcp:9008` endpoint
- **When** cleanup evaluates the failure
- **Then** it SHALL treat the desired absent-forward state as achieved

#### Scenario: Forward remains or cannot be checked

- **Given** forward removal fails and the forward remains or the forward list cannot be obtained
- **When** cleanup verifies the state
- **Then** the original removal error MUST propagate, with any listing failure attached as suppressed context

### Requirement: Android shutdown is verified when the server was running

After cleanup of a server that initially responded, Android stop MUST make up to six health probes separated by 500 ms. It SHALL return stopped-success text on the first failed health probe. If every probe still responds, it SHALL return warning text that another process may own port 9008 rather than throwing solely for that condition.

#### Scenario: Health endpoint goes down

- **Given** force-stop and forward cleanup completed and a later health probe fails
- **When** shutdown verification runs
- **Then** stop SHALL report success and identify the removed forward

#### Scenario: Port still responds

- **Given** all six post-stop health probes succeed
- **When** verification exhausts its attempts
- **Then** stop SHALL return text advising inspection of `adb forward --list`; as a returned string, the CLI SHALL print it to stdout and exit 0

### Requirement: Android stop is idempotent but does not hide toolchain failures

Repeated stop calls MUST succeed when the server and forward are already absent. A device-shell or unresolved forward-removal failure MUST propagate; the CLI maps an otherwise unmapped command-execution exception to generic exit code 1.

#### Scenario: Repeated teardown

- **Given** adb can reach the Android device, no Android server is running, and no `tcp:9008` forward remains
- **When** stop is called repeatedly
- **Then** each call SHALL perform best-effort cleanup and return that the server was not running

### Requirement: iOS stop controls the registrar-owned process

iOS stop MUST forcibly destroy the live `xcodebuild` process retained by the current `IOSAutomationToolRegistrar` instance and clear that reference. If no retained process exists or it is no longer alive, stop MUST clear the reference and return that the server is not running. It MUST NOT claim to discover or kill unrelated external `xcodebuild` processes, and it does not perform a post-destroy health check.

#### Scenario: Retained iOS process is alive

- **Given** the current registrar retains a live startup process
- **When** iOS stop runs
- **Then** it SHALL call `destroyForcibly`, clear the reference, and report success

#### Scenario: No retained live process

- **Given** the registrar has no live retained process
- **When** iOS stop runs
- **Then** it SHALL return an idempotent not-running result

### Requirement: Stop is exposed through MCP and CLI

The MCP contract MUST expose Android `stop_automation_server` and iOS `ios_stop_automation_server`. The CLI MUST expose one `stop_automation_server --platform android|ios` subcommand that delegates to the corresponding same internal stop operation and does not pre-require a running server.

#### Scenario: Platform facade selection

- **Given** a caller invokes the CLI stop subcommand with a valid platform
- **When** it dispatches
- **Then** Android SHALL use `AndroidStopToolRegistrar.stopAutomationServer` and iOS SHALL use `IOSAutomationToolRegistrar.stopAutomationServer`

## Verification

- Production lifecycle logic: `app/src/main/kotlin/com/example/visiontest/tools/AndroidStopToolRegistrar.kt`, `app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt`, `app/src/main/kotlin/com/example/visiontest/config/AutomationConfig.kt`
- Production facades: `app/src/main/kotlin/com/example/visiontest/cli/commands/StopAutomationServerCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/ComponentHolder.kt`, `app/src/main/kotlin/com/example/visiontest/ToolFactory.kt`
- Executable tests: `app/src/test/kotlin/com/example/visiontest/tools/AndroidStopToolRegistrarTest.kt`, `app/src/test/kotlin/com/example/visiontest/cli/CliCommandIntegrationTest.kt`, `app/src/test/kotlin/com/example/visiontest/McpStdioE2ETest.kt`
