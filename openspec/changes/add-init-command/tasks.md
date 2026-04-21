## 1. Embed Instructions as JAR Resource

- [ ] 1.1 Add Gradle `processResources` sync task to copy `AGENT_INSTRUCTIONS.md` from repo root to `app/src/main/resources/agent-instructions.md`
- [ ] 1.2 Verify resource loads from classpath in a unit test

## 2. Implement InitCommand

- [ ] 2.1 Create `InitCommand.kt` Clikt command with `--agent` option (comma-separated, validated against `claude`, `opencode`, `codex`)
- [ ] 2.2 Implement agent path mapping (claude → `.claude/skills/visiontest/SKILL.md`, opencode → `.opencode/skills/visiontest/SKILL.md`, codex → `.agents/skills/visiontest/SKILL.md`)
- [ ] 2.3 Implement SKILL.md writing with YAML frontmatter (`name`, `description`) + instructions content
- [ ] 2.4 Print confirmation output per agent and summary count
- [ ] 2.5 Register `InitCommand` in `VisionTestCli`

## 3. Tests

- [ ] 3.1 Unit test: valid single agent writes correct file to correct path
- [ ] 3.2 Unit test: comma-separated multiple agents writes all files
- [ ] 3.3 Unit test: invalid agent name produces error with exit code 2
- [ ] 3.4 Unit test: missing `--agent` flag produces error with exit code 2
- [ ] 3.5 Unit test: idempotent overwrite (run twice, same content)
- [ ] 3.6 Run full test suite: `./gradlew :app:test`

## 4. Remove Agent Setup from install.sh

- [ ] 4.1 Remove `download_agent_instructions()`, `install_agent_instructions()`, `append_with_markers()` functions and related constants/variables
- [ ] 4.2 Remove `--skip-agent-setup` flag parsing
- [ ] 4.3 Remove `download_agent_instructions` and `install_agent_instructions` calls from `main()`
- [ ] 4.4 Update summary output to mention `visiontest init --agent <agents>` for agent setup
- [ ] 4.5 Syntax-check install.sh: `bash -n install.sh`

## 5. Remove AGENT_INSTRUCTIONS.md from Release Workflow

- [ ] 5.1 Remove `AGENT_INSTRUCTIONS.md` from `.github/workflows/release.yaml` release assets

## 6. Update Documentation

- [ ] 6.1 Update `CLAUDE.md` to document `init` command
- [ ] 6.2 Update `README.md` to document `init` command
- [ ] 6.3 Update `docs/installation.md` to reference `init` instead of install.sh agent setup
