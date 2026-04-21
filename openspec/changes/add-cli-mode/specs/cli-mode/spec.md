## ADDED Requirements

### Requirement: Single entry point dispatches between MCP stdio and CLI modes

The `visiontest` JAR SHALL route invocations to the MCP stdio server when no arguments are passed (or the first argument is `serve`), and to the CLI subcommand dispatcher otherwise. The MCP stdio behavior MUST be byte-for-byte identical to the pre-change behavior when no arguments are passed, to preserve compatibility with existing agent hosts and the `install.sh` launcher.

#### Scenario: No-argument invocation runs the MCP stdio server

- **WHEN** `java -jar visiontest.jar` is executed with no arguments
- **THEN** the process starts the MCP server, connects `StdioServerTransport` to `System.in` / `System.out`, registers all existing MCP tools, and waits on `server.onClose` exactly as before this change

#### Scenario: Explicit `serve` subcommand runs the MCP stdio server

- **WHEN** `java -jar visiontest.jar serve` is executed
- **THEN** the behavior is identical to the no-argument case

#### Scenario: Unknown first argument enters the CLI dispatcher

- **WHEN** `java -jar visiontest.jar <anything-other-than-empty-or-serve>` is executed
- **THEN** the process constructs the CLI root command and delegates argument parsing to it; the MCP stdio server is NOT started for this invocation

### Requirement: `--platform` flag is required on every CLI subcommand

Every CLI subcommand SHALL require a `--platform` (alias `-p`) flag whose value is exactly `android` or `ios`. There MUST be no default, no environment-variable fallback, and no auto-detection. Android-only commands SHALL accept only `--platform android`.

#### Scenario: Missing `--platform` produces a usage error

- **WHEN** a CLI subcommand is invoked without `--platform`
- **THEN** the process prints a usage error to stderr and exits with code `2`

#### Scenario: Invalid `--platform` value is rejected

- **WHEN** a CLI subcommand is invoked with `--platform windows` (or any value other than `android` / `ios`)
- **THEN** the process prints a usage error naming the allowed values and exits with code `2`

#### Scenario: Android-only command rejects iOS platform

- **WHEN** `visiontest install_automation_server --platform ios` (or `visiontest press_back --platform ios`) is invoked
- **THEN** the process prints an error stating the command is Android-only and exits with code `5`

### Requirement: Exit codes are granular and LLM-scriptable

The CLI SHALL use a fixed, documented set of exit codes so that a skill or script can act on failures without parsing stderr:

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Generic failure (unhandled exception, underlying server crash) |
| 2 | Usage error (missing/invalid flag or argument) |
| 3 | Automation server not running / not reachable |
| 4 | Device or simulator not found |
| 5 | Platform not supported for this command |

#### Scenario: Success exits 0

- **WHEN** a CLI subcommand completes without error
- **THEN** the process prints the result to stdout and exits with code `0`

#### Scenario: Automation server not running maps to exit 3

- **WHEN** a CLI subcommand that requires a running automation server is invoked while the server is not reachable
- **THEN** the process prints to stderr a message instructing the caller to run `start_automation_server` and exits with code `3`

#### Scenario: Device not found maps to exit 4

- **WHEN** a CLI subcommand is invoked and `getFirstAvailableDevice()` fails because no device/simulator is connected
- **THEN** the process prints the underlying error message to stderr and exits with code `4`

#### Scenario: Unhandled exception maps to exit 1

- **WHEN** a CLI subcommand throws an unexpected exception during execution
- **THEN** the process prints the exception message to stderr and exits with code `1`

### Requirement: CLI commands share one implementation with MCP tools

Each CLI subcommand SHALL call the same `internal suspend` function that backs its MCP tool counterpart. The function MUST take typed parameters (not `CallToolRequest`). MCP behavior and output strings MUST be preserved exactly; the refactor is internal.

#### Scenario: MCP and CLI produce the same success message for the same inputs

- **WHEN** `android_tap_by_coordinates` is invoked via MCP with `x=100, y=200` AND `visiontest tap_by_coordinates --platform android 100 200` is invoked
- **THEN** both paths call the same underlying function and produce identical success text (modulo MCP's `TextContent` wrapping vs CLI's raw stdout)

#### Scenario: Existing MCP tests continue to pass after the extraction refactor

- **WHEN** `./gradlew :app:test` is executed after the handler-body extraction
- **THEN** all pre-existing MCP tool tests pass without modification to their assertions about tool output strings

### Requirement: MVP subcommand set

The CLI SHALL expose exactly the following 13 subcommands in the MVP. No more, no fewer.

| Subcommand | Platforms | Required args | Optional flags |
|------------|-----------|---------------|----------------|
| `install_automation_server` | android | — | — |
| `start_automation_server` | android, ios | — | — |
| `automation_server_status` | android, ios | — | — |
| `get_interactive_elements` | android, ios | — | `--include-disabled` |
| `get_ui_hierarchy` | android, ios | — | — |
| `get_device_info` | android, ios | — | — |
| `screenshot` | android, ios | — | `--output PATH` |
| `tap_by_coordinates` | android, ios | `x` `y` (ints) | — |
| `input_text` | android, ios | `text` (string) | — |
| `swipe_direction` | android, ios | `direction` (up\|down\|left\|right) | `--distance`, `--speed` |
| `press_back` | android | — | — |
| `press_home` | android, ios | — | — |
| `launch_app` | android, ios | `id` (string) | — |

#### Scenario: Deferred commands are not exposed

- **WHEN** the CLI help is listed
- **THEN** `find_element`, `swipe`, `swipe_on_element`, `list_apps`, `info_app`, `available_device`, and `ios_stop_automation_server` are NOT listed as subcommands

#### Scenario: `screenshot` default output path matches MCP behavior

- **WHEN** `visiontest screenshot --platform android` (or `ios`) is invoked without `--output`
- **THEN** the PNG is written to `./screenshots/<platform>_screenshot_<yyyyMMdd_HHmmss>.png` resolved against the CLI process's current working directory — identical to the MCP tool's default

#### Scenario: `swipe_direction` rejects invalid directions before dispatching

- **WHEN** `visiontest swipe_direction --platform android diagonal` is invoked
- **THEN** the process exits with code `2` and does NOT invoke the underlying automation function

### Requirement: Success output is prose on stdout; errors are prose on stderr

The CLI SHALL print the MCP tool's return string to stdout on success and to stderr on error. There MUST be no structured / JSON wrapping in the MVP. Logging output (from SLF4J loggers) MUST go to stderr so it doesn't contaminate captured stdout in scripts and skills.

#### Scenario: Success text goes to stdout

- **WHEN** `visiontest automation_server_status --platform android` succeeds with the server running
- **THEN** the success message is written to stdout (capturable via `$(...)` in a shell script) and stderr is empty for that invocation

#### Scenario: Error text goes to stderr

- **WHEN** `visiontest screenshot --platform android` is invoked while the Android automation server is not running
- **THEN** the error message is written to stderr and stdout is empty for that invocation

### Requirement: Reference skill ships with the CLI

The repository SHALL include a reference skill file at `.claude/skills/visiontest-mobile/SKILL.md` that teaches an LLM the standard automation loop through the CLI.

#### Scenario: Skill documents the standard loop

- **WHEN** an LLM loads the reference skill
- **THEN** the skill body includes: the `start_automation_server` → `screenshot` → `get_interactive_elements` → `tap_by_coordinates` loop, the rule that `--platform` is always required, the exit-code table with recommended actions per code, and the Flutter `contentDescription` gotcha
