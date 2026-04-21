## Why

VisionTest is currently only reachable through the MCP protocol, which works well when an agent runs inside an MCP-aware host (Claude Code, the Claude desktop app, etc.). But a growing use case is LLM agents driven by **skills** — small, focused instruction bundles that teach an agent to use a CLI. Skills are significantly simpler to distribute, configure, and debug than MCP server integrations, and they let any LLM (not just MCP-native hosts) drive VisionTest.

Today the only way to reach the underlying automation stack from a skill is to spawn the MCP JAR and speak stdio JSON-RPC to it per call — a bad UX and a gross integration boundary. A first-class CLI surface closes that gap: the same suspend functions that back the MCP tools become directly callable as `visiontest <command> --platform <android|ios> [args]`.

This change adds subcommand dispatch in the existing `Main.kt` entry point and exposes a curated MVP subset of commands — enough for an LLM skill to run the full automation loop (start server → inspect → interact → screenshot) — while leaving the MCP stdio server intact as the default (no-arg) behavior for backward compatibility.

## What Changes

- Add subcommand dispatch to `Main.kt`: when `args` is empty (or equals `serve`), run the existing MCP stdio server unchanged; otherwise route to the CLI dispatcher.
- Add a CLI dependency (clikt) and a new `cli/` package with one command class per exposed operation plus a root dispatcher.
- Refactor each tool handler body in the four `ToolRegistrar` implementations into a pure `suspend` function on the registrar (or a shared helper). The MCP side continues to wrap these with `ToolScope.tool { ... }`; the CLI side calls the same functions directly, so there is exactly one implementation per operation.
- Expose 13 MVP commands (see the `cli-mode` spec). Every command requires `--platform android|ios`, with two exceptions that are Android-only (`install_automation_server`, `press_back`) and therefore reject `--platform ios` with a clear error.
- Command names use the underscored, platform-less form (e.g. `tap_by_coordinates`, `press_home`) — a CLI-idiomatic spelling shared across platforms with the flag as the discriminator. The underlying MCP tool names (e.g. `android_tap_by_coordinates`, `ios_tap_by_coordinates`) are unchanged.
- Success output is the same prose string the MCP tool returns today (optimised for LLM consumption). Errors go to stderr with a granular non-zero exit code. A `--json` mode is explicitly deferred to a later change.
- Granular exit codes: `0` success, `1` generic failure, `2` usage error, `3` automation server not reachable, `4` device/simulator not found, `5` platform-not-supported-for-command.
- Add a reference skill (`.claude/skills/visiontest-mobile/SKILL.md`) that teaches an LLM the standard automation loop through the CLI. Intended both as in-repo dogfood and as a template users can copy into their own projects.

## Capabilities

### New Capabilities
- `cli-mode`: A first-class command-line interface to VisionTest's mobile automation operations, usable directly from shells, scripts, and LLM-driven skills. Covers the MVP subset of 13 commands spanning server lifecycle, inspection, interaction, navigation, and app launch on both Android and iOS.

### Modified Capabilities
<!-- None: the CLI is a new facade over the existing business logic. The MCP-side behavior of every tool is preserved exactly; the refactor to extract handler bodies is internal and invisible to MCP callers. -->

## Impact

- **MCP server (`app/`)** —
  - `Main.kt` gains a top-level `args`-based dispatch; empty args preserve current MCP stdio behavior.
  - New `app/src/main/kotlin/com/example/visiontest/cli/` package housing the root `VisionTestCli` command and 13 subcommand classes.
  - Each `ToolRegistrar` grows `internal suspend` functions carrying the body of each tool (arg extraction stays in the MCP handler; the *work* moves into the shared function). Existing MCP behavior and test coverage is preserved.
  - New dependency: `com.github.ajalt.clikt:clikt:4.x` (pure-JVM, no native or reflection surprises; matches the existing "no magic numbers, plain Kotlin" posture of the codebase).
- **Automation servers (`automation-server/`, `ios-automation-server/`)** — Unchanged. The CLI talks to them through the exact same `AutomationClient` / `IOSAutomationClient` paths the MCP server already uses.
- **Launcher / release** — `install.sh` and the GitHub Actions release workflow need no changes: the launcher script still runs `java -jar visiontest.jar "$@"`, and `"$@"` now reaches the CLI dispatcher when the user passes args. Current no-arg MCP invocations (Claude Code, Claude desktop) continue to work unchanged.
- **Tests** — New pure-JVM unit tests for (a) each CLI subcommand's argument parsing and delegation, (b) the root dispatcher's `serve` vs subcommand routing, (c) exit-code mapping for each error class. MCP-side tests are unchanged except where they need to follow the handler-body extraction refactor (mechanical updates).
- **Docs** —
  - `CLAUDE.md` gains a new "CLI Usage" section with the full command list and flag reference.
  - A reference `.claude/skills/visiontest-mobile/SKILL.md` ships in the repo.
  - `LEARNING.md` gets a short entry explaining the dual-facade (MCP + CLI) pattern and the handler-extraction refactor.
- **External surface** — New CLI commands. No breaking changes to MCP tools or automation-server JSON-RPC methods.
