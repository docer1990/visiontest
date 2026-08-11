## Why

Users need a single, predictable way to configure their AI coding agent with VisionTest CLI instructions at the project level. Currently, `install.sh` writes to global home-directory config files, which can't be shared with teammates via version control. A `visiontest init` command writes project-local config that can be committed and shared.

## What Changes

- Add `visiontest init --agent <agents>` CLI command that writes SKILL.md files to the correct project-level paths for each specified agent
- Bundle `AGENT_INSTRUCTIONS.md` as a JAR resource so init works offline
- Remove all agent instruction setup from `install.sh` (the installer only handles the CLI binary)
- Remove `AGENT_INSTRUCTIONS.md` from GitHub Release assets (no longer downloaded separately)

## Capabilities

### New Capabilities
- `init-command`: The `visiontest init --agent <agents>` CLI command that writes agent-specific SKILL.md files to project directories

### Modified Capabilities

## Impact

- `install.sh`: Agent setup code removed (~120 lines), `--skip-agent-setup` flag removed
- `app/` module: New `InitCommand.kt`, new resource file, Gradle build task to sync resource
- `.github/workflows/release.yaml`: Remove `AGENT_INSTRUCTIONS.md` from release assets
- Documentation: `CLAUDE.md`, `README.md`, `docs/installation.md` updated to reference `init`
