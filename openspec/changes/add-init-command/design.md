## Context

VisionTest is an MCP server + CLI for mobile automation. The CLI is distributed as a fat JAR via `install.sh`. Currently, `install.sh` also installs AI agent instructions into global home-directory config files. This approach can't be shared via version control and mixes two concerns (binary installation vs. project configuration).

Three AI coding agents are targeted: Claude Code, OpenCode, and Codex. All three support a project-level SKILL.md format with YAML frontmatter, differing only in the directory path.

## Goals / Non-Goals

**Goals:**
- Single `visiontest init --agent <agents>` command for project-level agent setup
- Support Claude Code, OpenCode, and Codex with correct project-level paths
- Embedded instructions (JAR resource) -- no network required
- Idempotent: running twice produces the same result
- Remove agent setup from `install.sh`

**Non-Goals:**
- Interactive agent selection (menus/prompts)
- Global/home-directory agent setup
- Gemini, Copilot, or other agents (future addition)
- MCP server configuration

## Decisions

### 1. Embedded JAR resource over network download
**Decision**: Bundle `AGENT_INSTRUCTIONS.md` as `app/src/main/resources/agent-instructions.md`.
**Rationale**: The file is ~90 lines. Embedding avoids network calls, version mismatch, and failure modes. A Gradle `processResources` sync task keeps the repo-root file as the single source of truth.
**Alternative**: Download from GitHub Release at init time -- rejected due to unnecessary complexity.

### 2. Unified SKILL.md format for all agents
**Decision**: All three agents get the same SKILL.md content with YAML frontmatter. Only the target directory differs.
**Rationale**: Claude Code, OpenCode, and Codex all support the same `SKILL.md` format with `name` and `description` frontmatter fields. This eliminates per-agent formatting logic.

### 3. Comma-separated `--agent` flag
**Decision**: `--agent claude,opencode,codex` with comma separation.
**Rationale**: Simpler than repeatable flags, familiar pattern (e.g., `git log --format`). Clikt supports `split(",")` natively on options.

### 4. No platform flag
**Decision**: `InitCommand` does not require `--platform`. It is registered directly in `VisionTestCli`, not through the platform-gated command structure.
**Rationale**: Agent setup is not a device operation.

### 5. Agent path mapping

| Agent | Project path |
|-------|-------------|
| `claude` | `.claude/skills/visiontest/SKILL.md` |
| `opencode` | `.opencode/skills/visiontest/SKILL.md` |
| `codex` | `.agents/skills/visiontest/SKILL.md` |

Sources: Claude Code docs, OpenCode docs (opencode.ai/docs/skills), Codex docs (developers.openai.com/codex/skills).

## Risks / Trade-offs

- **[Agent path changes]** If Claude/OpenCode/Codex change their skill discovery paths, init writes to the wrong location. Mitigation: paths are well-documented standards; adding new paths is a one-line map entry.
- **[Stale instructions after upgrade]** After updating VisionTest, the project SKILL.md still has old content until `init` is re-run. Mitigation: acceptable -- same pattern as any config file. Document in help output.
- **[CWD assumption]** Init writes relative to CWD. If run from the wrong directory, files go to the wrong place. Mitigation: print the absolute path in output so the user sees where files were written.
