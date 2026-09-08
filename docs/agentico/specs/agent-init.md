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
containing `name: visiontest` and an activation-oriented description; it MUST
NOT fetch instructions from the network. The embedded body MUST be
self-contained and MUST NOT depend on paths in the VisionTest source repository.

#### Scenario: Installed JAR initializes offline

- **Given** the fat JAR contains `agent-instructions.md` and no network is available
- **When** init writes a supported agent skill
- **Then** the generated file SHALL contain the hardcoded frontmatter followed by the embedded instruction body

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

## Verification

- Production command and resource packaging: `app/src/main/kotlin/com/example/visiontest/cli/commands/InitCommand.kt`, `app/src/main/kotlin/com/example/visiontest/cli/VisionTestCli.kt`, `app/src/main/resources/agent-instructions.md`
- Executable tests: `app/src/test/kotlin/com/example/visiontest/cli/InitCommandTest.kt`, `app/src/test/kotlin/com/example/visiontest/cli/InitCommandE2ETest.kt`, `app/src/test/kotlin/com/example/visiontest/cli/VisionTestCliTest.kt`
