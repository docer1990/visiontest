# Installation & Distribution

## One-Command Installer (`install.sh`)

Users install with `curl -fsSL https://github.com/docer1990/visiontest/releases/latest/download/install.sh | bash`. The script:
1. Detects OS (macOS/Linux) and arch (arm64/x86_64)
2. Validates Java 17+ with platform-specific install suggestions
3. Fetches latest release tag from GitHub API, validates format (`v[0-9][0-9A-Za-z._-]*`) and rejects dangerous characters
4. Downloads `visiontest.jar` + SHA-256 checksum, verifies integrity
5. Downloads Android APKs (`automation-server.apk`, `automation-server-test.apk`) + checksums, verifies integrity
6. On macOS arm64: downloads `ios-automation-server.tar.gz` + checksum, extracts pre-built iOS XCUITest bundle to `ios-automation-server/` subdirectory (skipped on Linux and macOS x86_64)
7. Installs JAR, APKs, and iOS bundle to `~/.local/share/visiontest/` (customizable via `VISIONTEST_DIR` env var, must be under `$HOME`)
8. Creates wrapper script at `~/.local/bin/visiontest`, ensures PATH
9. Downloads `AGENT_INSTRUCTIONS.md` and installs VisionTest CLI instructions into detected AI coding agents:
   - **Claude Code** (`claude`): creates skill at `~/.claude/skills/visiontest-mobile/SKILL.md`
   - **OpenCode** (`opencode`): appends to `~/.config/opencode/AGENTS.md`
   - **Codex** (`codex`): appends to `~/.codex/instructions.md`
   - **Copilot CLI** (`gh copilot`): appends to `~/.github/copilot-instructions.md`
   - Uses `<!-- BEGIN/END VISIONTEST -->` markers for idempotent updates
   - Skip with `--skip-agent-setup` flag
10. Does not modify Claude Desktop configuration; use `run-visiontest.sh` or manual setup for Claude integration.

**Security hardening:** `umask 077`, explicit `chmod` on all files/dirs, tag validation, checksum verification, install path restricted to `$HOME`.

## Release Workflow (`.github/workflows/release.yaml`)

Triggered by git tags matching `v*`. The workflow runs the test suite, builds the fat JAR via `shadowJar`, Android APKs, and the pre-built iOS XCUITest bundle (on a macOS runner), generates SHA-256 checksums, and creates a GitHub Release with the following assets: `visiontest.jar`, `visiontest.jar.sha256`, `automation-server.apk`, `automation-server.apk.sha256`, `automation-server-test.apk`, `automation-server-test.apk.sha256`, `ios-automation-server.tar.gz`, `ios-automation-server.tar.gz.sha256`, `AGENT_INSTRUCTIONS.md`, `install.sh`, `run-visiontest.sh`.

All GitHub Actions in both workflows are pinned to commit SHAs for supply-chain security. When updating or adding actions, always use SHA-pinned references instead of floating version tags.

## Launcher Script (`run-visiontest.sh`)

Used for development and Claude Desktop config. JAR resolution order:

1. Repo build: `app/build/libs/visiontest.jar` (sets up `ANDROID_HOME`, APK path, `cd` to project root)
2. Installed JAR: `~/.local/share/visiontest/visiontest.jar` (skips Android SDK setup)
3. Error with build/install instructions

## Prerequisites

- JDK 17+
- macOS or Linux (arm64 or x86_64)
- Android Platform Tools (ADB) in PATH — for Android automation
- Xcode Command Line Tools — for iOS simulator support (macOS only). Pre-built iOS bundle requires the same Xcode major version used in CI (see release notes). For source builds or Intel Macs, the full Xcode IDE is needed.
- Android SDK — only needed for building the automation-server module from source

> **Quick start:** Users who just need the MCP server can run `curl -fsSL https://github.com/docer1990/visiontest/releases/latest/download/install.sh | bash` — only Java 17+ is required.

## CLI Usage

After installation, `visiontest` with no arguments starts the MCP stdio server (unchanged behavior). To use the CLI, pass a subcommand:

```bash
visiontest screenshot --platform android
visiontest get_interactive_elements --platform ios
visiontest tap_by_coordinates --platform android 540 1200
```

Every command requires `--platform android` or `--platform ios`. Run `visiontest --help` for the full command list. See `CLAUDE.md` for the complete CLI reference.
