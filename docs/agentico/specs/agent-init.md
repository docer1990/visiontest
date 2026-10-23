# Agent initialization

## Purpose

Agent initialization installs VisionTest usage instructions into project-local skill locations for supported coding agents without coupling project configuration to binary installation or network access.

## Requirements

### Requirement: Init maps supported agent names to project-local paths

`visiontest init` MUST support `claude`, `opencode`, and `codex` and SHALL map them respectively to `.claude/skills/visiontest/SKILL.md`, `.opencode/skills/visiontest/SKILL.md`, and `.agents/skills/visiontest/SKILL.md` beneath the process current working directory.

#### Scenario: One supported agent

- **Given** the current working directory is a project and `--agent claude` is supplied
- **When** initialization runs
- **Then** it SHALL create parent directories as needed and write `.claude/skills/visiontest/SKILL.md`

#### Scenario: Multiple supported agents

- **Given** `--agent claude,opencode,codex` is supplied
- **When** initialization runs
- **Then** it SHALL write all three mapped files and report each path plus a summary count

### Requirement: Agent selection is comma-separated and validated as a whole

The required `--agent` option MUST split on commas and trim surrounding whitespace from each value. Every trimmed value MUST be nonblank and present in the supported mapping before any file is written.

#### Scenario: Whitespace around names

- **Given** supported names contain surrounding spaces after comma splitting
- **When** values are validated
- **Then** the trimmed names SHALL select their normal mapped paths

#### Scenario: Unknown or blank name

- **Given** any requested value is unknown or blank
- **When** validation occurs
- **Then** initialization MUST write no files, MUST list the validation problem and valid names on stderr, and MUST exit with code 2

#### Scenario: Missing option

- **Given** `visiontest init` is invoked without `--agent`
- **When** Clikt parses the command
- **Then** it SHALL report a missing required option and exit with code 2

### Requirement: Instructions are embedded for offline use

The build MUST package
`app/src/main/resources/agent-instructions.md` as a classpath resource with the
same name. Init MUST load that resource locally and prepend YAML frontmatter
containing `name: visiontest` and an activation-oriented description, followed
by a generated-by line that identifies the runtime JAR `Implementation-Version`;
it MUST NOT fetch instructions from the network. The embedded body MUST be
self-contained and MUST NOT depend on paths in the VisionTest source repository.

#### Scenario: Installed JAR initializes offline

- **Given** the fat JAR contains `agent-instructions.md` and no network is available
- **When** init writes a supported agent skill
- **Then** the generated file SHALL contain the hardcoded frontmatter followed by the embedded instruction body

#### Scenario: Generated skill identifies its source version

- **Given** the CLI runs from a packaged JAR with `Implementation-Version` set
- **When** init writes a supported agent skill
- **Then** the generated file SHALL state that version between its frontmatter and instructions

#### Scenario: Embedded resource is absent

- **Given** the runtime classpath does not contain `agent-instructions.md`
- **When** init tries to load it
- **Then** it MUST fail before writing files with a generic failure message and exit code 1

### Requirement: Reinitialization overwrites deterministically

Init MUST overwrite an existing mapped `SKILL.md` with the complete current generated content. Repeating the same invocation against unchanged embedded instructions MUST produce identical file content.

#### Scenario: Existing skill file

- **Given** a mapped skill file already exists
- **When** the same agent is initialized again
- **Then** the file SHALL be replaced without prompting and its content SHALL equal a fresh initialization

### Requirement: Init has no device-platform dependency

The `init` command MUST NOT define or require `--platform`, instantiate device components, contact an automation server, or require a connected device.

#### Scenario: Init without platform

- **Given** a supported `--agent` value and a writable project directory
- **When** `visiontest init` runs without `--platform`
- **Then** it SHALL perform project-local initialization normally

## Usage guidance

The embedded skill MUST describe only the CLI workflow. MCP setup and usage
remain in the product documentation, not in the distributed skill. Performance
tracing guidance likewise belongs in the performance documentation. Keep the
skill focused on routine testing, with no dependency on those external references.
CLI examples MUST distinguish Android APK installation from iOS bundle setup.
Require checking operation results and expected app state rather than assuming a
successful call proves app behavior. Visual claims require inspecting image evidence.

The guidance MUST prioritize targeted element queries for known selectors and
filtered element discovery for unfamiliar screens. Built-in waiting and targeted
interactions SHOULD replace manual polling and redundant lookup/wait/action
sequences. Full hierarchy inspection and screenshots SHALL be reserved for
information missing from narrower queries, visual checks, or user-requested
evidence. Functional examples MUST NOT mandate routine before/after screenshots.
Output reduction MUST preserve operation outcomes and errors and MUST NOT treat
a partial view as proof of absence. Token and latency savings require measurement.

When accessibility data identifies an offscreen target and its scrollable region,
the guidance SHALL permit scrolling without a preliminary screenshot. It MUST
distinguish tree presence from actionability and Android's visible-element
filtering from iOS snapshot inclusion. Element swipes SHALL target the visible
container; they MUST NOT be described as automatic scroll-until-found operations.
Agents SHALL verify progress and bound repeated gestures.

The guidance SHALL focus execution on the requested assertions and reuse verified
session setup until a relevant change or failure invalidates it. A reused app
build MUST contain the code under test. Intermediate checks MAY be omitted when
a specific downstream assertion covers the same requirement; requested checks
and test isolation MUST be preserved. Actions on one device SHALL be serialized.
Retries SHALL follow new evidence or a corrective action. Failed prerequisites
SHALL block dependent steps rather than produce misleading additional failures.

The guidance MUST reuse a healthy, compatible automation server on the same
target across consecutive tests and retries. Cleanup SHALL happen at the end
of the requested batch or testing task, not after each test, and SHALL stop only
a server started by the agent unless the user asks to leave it running.
Servers already running before the session SHALL remain active.

## Verification

- Production command and resource packaging: `app/src/main/kotlin/com/example/visiontest/cli/commands/InitCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/VisionTestCli.kt`, `app/src/main/resources/agent-instructions.md`
- Executable tests: `app/src/test/kotlin/com/example/visiontest/cli/InitCommandTest.kt`, `app/src/test/kotlin/com/example/visiontest/cli/InitCommandE2ETest.kt`, `app/src/test/kotlin/com/example/visiontest/cli/VisionTestCliTest.kt`

## Native lifecycle and evidence guidance

The embedded skill SHALL lead with the routine CLI workflow and follow the
application repository's build/install instructions, including artifact, variant,
simulator build and configuration. It SHALL contain an offline-usable native
Android/iOS reference for opening URLs, terminating apps, clearing Android app
data and uninstalling apps. Native commands SHALL use the chosen serial/UDID
explicitly, with the established package/bundle ID, and explain argument quoting,
exit status and diagnostics. Android URL examples SHALL preserve quoting through
both host and device shells. Accepted launch requests MUST be followed by an
application outcome check. Deep links MUST NOT bypass navigation under test.

Reset/uninstall SHALL be scenario-specific. iOS uninstall with project-guided
reinstall MUST NOT be described as equivalent to Android data clearing or as a
complete reset of Keychain, shared or remote state. Guidance MUST retain the
single-target limitation and MUST NOT claim backend identity verification.

The skill SHALL explicitly replace fixed UI sleeps and agent-written polling
with built-in selector waits and `wait_for_element` for supported conditions.
It SHALL distinguish immediate lookup, target actionability and subsequent app
outcomes, without claiming all operations wait or that internal polling is absent.
Structured find/wait results SHALL count as functional UI evidence. Screenshots
SHALL NOT be required merely because evidence was requested, unless image evidence
or visual verification is needed.

Test flows SHOULD include `--record` by default in agent guidance. The CLI flag
remains opt-in. The skill SHALL explain exceptions for user preferences, recorder
limitations and incompatible time budgets, report omitted recording, and preserve
recording failures rather than silently dropping the requirement. Video SHALL NOT
be treated as automatic verification or require routine video analysis. Completed
actions MUST NOT be replayed solely to repair evidence collection.

Guidance SHALL retain stdout results, stderr diagnostics and command exit status.
It MUST NOT recommend filtering VisionTest output with `grep -v INFO` or discarding
stderr. Returned operation errors and uncertain action outcomes remain significant.
