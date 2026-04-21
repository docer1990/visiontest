# Design: `visiontest init` Command

## Summary

Add a `visiontest init --agent <agents>` command that configures the current project directory with VisionTest CLI instructions for the specified AI coding agents. This is the single mechanism for agent instruction setup -- `install.sh` only handles the CLI binary.

## Motivation

Users who install VisionTest need a clear, predictable way to teach their AI coding agent about VisionTest commands. Rather than spreading this across global home-directory config (hard to share with teammates) and the installer, a single `init` command writes project-level config that can be committed to version control and shared with the team.

## Usage

```bash
visiontest init --agent claude           # Claude Code only
visiontest init --agent opencode         # OpenCode only
visiontest init --agent codex            # Codex only
visiontest init --agent claude,opencode  # Multiple agents (comma-separated)
```

## Supported Agents and Target Paths

All agents use the SKILL.md format with YAML frontmatter. The only difference is the directory path relative to the current working directory:

| Agent value | Target path (relative to CWD) |
|-------------|-------------------------------|
| `claude`    | `.claude/skills/visiontest/SKILL.md` |
| `opencode`  | `.opencode/skills/visiontest/SKILL.md` |
| `codex`     | `.agents/skills/visiontest/SKILL.md` |

### SKILL.md Format

Each agent gets the same file content:

```markdown
---
name: visiontest
description: Automate mobile device interactions (Android/iOS) via the VisionTest CLI. Use when testing, inspecting, or interacting with mobile apps through screenshots, taps, swipes, and text input.
---

<contents of AGENT_INSTRUCTIONS.md>
```

## Architecture

### Embedded Resource

`AGENT_INSTRUCTIONS.md` is copied to `app/src/main/resources/agent-instructions.md` and bundled inside the JAR. A Gradle `processResources` task (or a `Copy` task) syncs the repo-root file into resources at build time so there's a single source of truth. The `init` command reads it from the classpath at runtime -- no network calls, no external file dependencies.

### New Clikt Command: `InitCommand`

- Registered in `VisionTestCli` alongside the other commands
- Does NOT require `--platform` (this is not a device command)
- Has a required `--agent` option that accepts a comma-separated list of agent names
- Validates agent names against the supported set (`claude`, `opencode`, `codex`)
- For each valid agent, creates the target directory and writes the SKILL.md file

### Agent Writer

A single function handles all agents since the format is identical -- only the path differs:

```
fun writeSkill(agentPath: Path, instructions: String)
```

This:
1. Creates parent directories if needed
2. Writes the SKILL.md with YAML frontmatter + instructions content
3. Reports success per agent

### Idempotency

Running `init` twice produces the same result. The SKILL.md file is overwritten each time. This also serves as the upgrade path -- after updating VisionTest, re-running `init` updates the instructions.

## Changes to install.sh

Remove all agent-related code:
- Remove `download_agent_instructions()` function
- Remove `install_agent_instructions()` function  
- Remove `append_with_markers()` function
- Remove `MARKER_BEGIN` / `MARKER_END` constants
- Remove `--skip-agent-setup` flag
- Remove `SKIP_AGENT_SETUP` variable
- Remove the `download_agent_instructions` and `install_agent_instructions` calls from `main()`
- Update the summary output to mention `visiontest init` instead

The `AGENT_INSTRUCTIONS.md` file at the repo root remains as the canonical source (copied into resources at build time). The release workflow no longer needs to publish it as a separate asset.

## Output

```
$ visiontest init --agent claude,opencode
Initializing VisionTest for: /Users/me/myapp
  > Claude Code: .claude/skills/visiontest/SKILL.md
  > OpenCode: .opencode/skills/visiontest/SKILL.md
Done! 2 agent(s) configured.
```

## Error Handling

| Condition | Behavior |
|-----------|----------|
| `--agent` not provided | Print error with valid agent names, exit 2 |
| Unknown agent name in list | Print error listing invalid name(s) and valid options, exit 2 |
| File write failure | Print error for that agent, continue with remaining agents, exit 1 if any failed |
| Not in a project directory | Not validated -- writes relative to CWD (user's responsibility) |

## Testing

- Unit test: `InitCommand` with a temp directory as CWD, verify file contents for each agent
- Unit test: comma-separated parsing, invalid agent names, missing `--agent`
- Unit test: idempotent overwrite (run twice, same result)
- Integration: embedded resource loads correctly from classpath

## Files Changed

| File | Change |
|------|--------|
| `app/src/main/resources/agent-instructions.md` | NEW -- copy of `AGENT_INSTRUCTIONS.md` |
| `app/src/main/kotlin/.../cli/commands/InitCommand.kt` | NEW -- Clikt command |
| `app/src/main/kotlin/.../cli/VisionTestCli.kt` | Register `InitCommand` |
| `app/src/test/kotlin/.../cli/InitCommandTest.kt` | NEW -- tests |
| `install.sh` | Remove agent setup code, update summary |
| `.github/workflows/release.yaml` | Remove `AGENT_INSTRUCTIONS.md` from release assets |
| `CLAUDE.md` | Document `init` command |
| `README.md` | Document `init` command |
| `docs/installation.md` | Update agent setup docs to reference `init` |

## Out of Scope

- Interactive agent selection (menu/prompt)
- Global/home-directory installation (removed from install.sh)
- Gemini, Copilot, or other agents (can be added later with a new path entry)
- MCP server configuration (init only handles CLI instructions)
