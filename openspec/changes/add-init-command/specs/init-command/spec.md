## ADDED Requirements

### Requirement: Init command writes SKILL.md for specified agents
The system SHALL provide a `visiontest init --agent <agents>` command that writes a SKILL.md file to the correct project-level directory for each specified agent. The `--agent` option SHALL accept a comma-separated list of agent names.

#### Scenario: Single agent initialization
- **WHEN** user runs `visiontest init --agent claude` in a project directory
- **THEN** the system writes `.claude/skills/visiontest/SKILL.md` with YAML frontmatter and VisionTest CLI instructions

#### Scenario: Multiple agent initialization
- **WHEN** user runs `visiontest init --agent claude,opencode,codex`
- **THEN** the system writes SKILL.md files to `.claude/skills/visiontest/SKILL.md`, `.opencode/skills/visiontest/SKILL.md`, and `.agents/skills/visiontest/SKILL.md`

#### Scenario: Output confirmation
- **WHEN** initialization completes successfully
- **THEN** the system prints the path of each written file and a summary count

### Requirement: Supported agent path mapping
The system SHALL map agent names to project-level paths as follows:
- `claude` → `.claude/skills/visiontest/SKILL.md`
- `opencode` → `.opencode/skills/visiontest/SKILL.md`
- `codex` → `.agents/skills/visiontest/SKILL.md`

#### Scenario: Claude Code path
- **WHEN** agent is `claude`
- **THEN** SKILL.md is written to `.claude/skills/visiontest/SKILL.md`

#### Scenario: OpenCode path
- **WHEN** agent is `opencode`
- **THEN** SKILL.md is written to `.opencode/skills/visiontest/SKILL.md`

#### Scenario: Codex path
- **WHEN** agent is `codex`
- **THEN** SKILL.md is written to `.agents/skills/visiontest/SKILL.md`

### Requirement: SKILL.md format
Each SKILL.md SHALL contain YAML frontmatter with `name: visiontest` and a `description` field, followed by the VisionTest CLI instructions content.

#### Scenario: File content structure
- **WHEN** a SKILL.md is written for any agent
- **THEN** the file starts with `---` YAML frontmatter containing `name` and `description`, followed by the CLI instructions markdown

### Requirement: Instructions embedded as JAR resource
The VisionTest CLI instructions SHALL be bundled inside the JAR as a classpath resource. The `init` command SHALL read instructions from the classpath at runtime without network access.

#### Scenario: Offline initialization
- **WHEN** user runs `visiontest init --agent claude` without network connectivity
- **THEN** initialization succeeds using the embedded resource

### Requirement: Idempotent initialization
Running `visiontest init` multiple times with the same arguments SHALL produce the same result. Existing SKILL.md files SHALL be overwritten with current content.

#### Scenario: Re-running init
- **WHEN** user runs `visiontest init --agent claude` twice in the same directory
- **THEN** the SKILL.md file contains the same content as after the first run

### Requirement: Error handling for invalid agent names
The system SHALL reject unknown agent names and print an error listing valid options.

#### Scenario: Unknown agent name
- **WHEN** user runs `visiontest init --agent gemini`
- **THEN** the system prints an error with the list of valid agent names and exits with code 2

#### Scenario: Missing agent flag
- **WHEN** user runs `visiontest init` without `--agent`
- **THEN** the system prints usage help and exits with code 2

### Requirement: No platform flag required
The `init` command SHALL NOT require the `--platform` flag since agent setup is not a device operation.

#### Scenario: Init without platform
- **WHEN** user runs `visiontest init --agent claude`
- **THEN** the command succeeds without requiring `--platform`

### Requirement: Remove agent setup from install.sh
The installer script SHALL NOT install agent instructions. All agent instruction functions, markers, and the `--skip-agent-setup` flag SHALL be removed from `install.sh`. The installer summary SHALL reference `visiontest init` for agent setup.

#### Scenario: Clean install without agent setup
- **WHEN** user runs `install.sh`
- **THEN** no agent config files are created in the home directory or project
- **THEN** the summary output mentions `visiontest init` for agent configuration
