## Context

VisionTest's MCP tool registrations in `app/src/main/kotlin/com/example/visiontest/tools/` mix three concerns in each handler:

1. **Arg extraction** — reading typed parameters out of `CallToolRequest` via `requireString`, `optionalInt`, etc.
2. **Business logic** — checking server state, calling `AutomationClient` / `IOSAutomationClient` / `Android` / `IOSManager`, parsing responses, formatting a human-readable result string.
3. **Framing** — `ToolScope.tool { ... }` wraps the handler with `withTimeout`, `TimeoutCancellationException` → `TimeoutException`, and `ErrorHandler.handleToolError(...)` for error mapping into `CallToolResult`.

(2) is identical between the MCP and CLI use cases. (1) and (3) are MCP-specific. To support a CLI without duplicating (2), the business logic needs to be extractable from under the MCP framing — which is exactly what we do.

The underlying clients (`AutomationClient`, `IOSAutomationClient`, `Android`, `IOSManager`) are already MCP-free suspend-function surfaces. Some tool registrars also contain helper logic with no MCP dependency (e.g. `AndroidAutomationToolRegistrar.captureScreenshot`, `resolveScreenshotPath`, `writeScreenshot` are already `internal suspend` functions). The refactor extends this pattern to every tool: the body of each tool becomes an `internal suspend` function taking plain typed parameters, and the MCP registration shrinks to "read args → call function → return string".

Prior art for `main(args)` dispatch in a Kotlin/JVM app with clikt is straightforward:

```
main(args):
  if args.isEmpty() or args[0] == "serve":
    <existing MCP stdio setup>
  else:
    VisionTestCli().main(args)
```

The existing MCP path is unchanged when args are empty (or `serve`), which keeps the Claude Code / Claude desktop launchers working without any config change.

## Goals / Non-Goals

**Goals:**
- Expose the MVP subset of 13 automation operations as CLI commands usable from shells, scripts, and LLM skills.
- Share one implementation per operation between MCP and CLI (no business-logic duplication).
- Preserve the current MCP stdio behavior exactly when `visiontest` is invoked with no args.
- Give LLM consumers output that's prose-rich (good for chain-of-thought) and errors with granular exit codes (good for retry logic).
- Ship a reference LLM skill that teaches the standard automation loop through the CLI.
- Require `--platform` explicitly on every command — no implicit defaults that could silently target the wrong OS.

**Non-Goals:**
- A `--json` output mode. The MCP tool strings today are LLM-friendly and stable; `--json` is deferred to a later change once we know which commands actually need structured output for scripting.
- A long-running daemon / `visiontestd` process. Each CLI invocation spins up a fresh JVM (~1–2 s cold). The Android automation server on-device persists between invocations, so only the first call in a session pays the setup cost; subsequent commands reuse the running server. Daemon mode can be added later if interactive use emerges.
- Exposing every MCP tool. `find_element`, `swipe`, `swipe_on_element`, `list_apps`, `info_app`, `available_device`, `ios_stop_automation_server` are deliberately deferred to a post-MVP change once we see what's actually friction-inducing in practice.
- Renaming MCP tools or changing the MCP output format. MCP-side behavior is preserved exactly.
- Extracting a separate `core` Gradle module. The refactor keeps handler bodies on the existing `ToolRegistrar` classes (or as top-level functions in the `tools/` package). A module split is a bigger refactor that can happen later if the CLI and MCP surfaces diverge further.

## Decisions

### Decision 1: Dispatch via `main(args)` branching, not separate `main` per mode

**Choice:** One `main(args)` function. If `args` is empty or `args[0] == "serve"`, run the existing MCP stdio server. Otherwise, construct a clikt-based `VisionTestCli` root command and delegate to it.

**Alternatives considered:**
- **Separate entry points (`MainMcp.kt`, `MainCli.kt`) with different Gradle tasks:** Doubles the launcher surface. Users and automation hosts already call `java -jar visiontest.jar`; asking them to call a different JAR or pass `-Dmode=cli` is a worse UX than a single entry point that dispatches on args.
- **Clikt "default subcommand":** Clikt supports invoking a default subcommand when no subcommand is given, but the MCP path doesn't fit cleanly inside a clikt command — it consumes stdin as a binary protocol and blocks indefinitely, whereas clikt commands expect typed parse → run → return. Branching before entering clikt avoids this impedance mismatch.

**Rationale:** The simplest backward-compatible shape. Preserves every existing MCP launcher (including `install.sh`'s `~/.local/bin/visiontest` wrapper and the shadowJar `-Main-Class`) without configuration changes.

### Decision 2: Extract each tool's body into an `internal suspend` function on its registrar

**Choice:** For every tool currently registered in `AndroidAutomationToolRegistrar`, `IOSAutomationToolRegistrar`, `AndroidDeviceToolRegistrar`, and `IOSDeviceToolRegistrar`, move the handler body into an `internal suspend` function whose parameters are the typed inputs (not a `CallToolRequest`). The MCP registration becomes a thin wrapper:

```kotlin
scope.tool(name = "android_tap_by_coordinates", ...) { request ->
    tapByCoordinates(request.requireInt("x"), request.requireInt("y"))
}

internal suspend fun tapByCoordinates(x: Int, y: Int): String { /* body */ }
```

The CLI subcommand calls `tapByCoordinates(x, y)` directly.

**Alternatives considered:**
- **A new `core` Gradle module holding pure "service" classes:** Bigger refactor, forces package moves across the repo, and creates a symmetry between MCP and CLI that's not needed yet. We can split later if the two facades grow apart.
- **Put extracted functions as top-level in the `tools/` package:** Works, but scatters related code. Keeping them on the registrar preserves the existing file layout and keeps per-tool state (e.g. references to `automationClient`, `discovery`) in scope without wider refactor.

**Rationale:** Minimal churn, maximum reuse. The registrars already contain per-tool helpers for complex cases (`captureScreenshot`, `resolveScreenshotPath`, `writeScreenshot` in `AndroidAutomationToolRegistrar`); this generalises that pattern.

### Decision 3: `--platform android|ios` is a required flag on every CLI command

**Choice:** Every CLI subcommand accepts `--platform` (short form `-p`) and requires a value of exactly `android` or `ios`. No default. No env-var fallback. No auto-detection from "which device is connected".

The two Android-only commands (`install_automation_server`, `press_back`) reject `--platform ios` with exit code `5` (platform-not-supported-for-command) and a clear error message.

**Alternatives considered:**
- **Default to the only connected device:** Convenient for humans, dangerous for LLMs — an agent could silently target the wrong platform when both are running. Skill authors can't easily predict what's connected in a user's environment.
- **Separate top-level subcommands (`visiontest android tap ...`, `visiontest ios tap ...`):** Nested subcommands read more naturally to humans but bloat the command tree and make the two platforms feel like separate products. The flag form keeps platform as a dimension of every call and scales cleanly if we ever add a third platform.
- **Env var `VISION_TEST_PLATFORM`:** Hidden state is bad for LLMs — an agent can't see what it's about to do. Env vars in skill instructions also compose poorly when the same agent switches platforms mid-session.

**Rationale:** Explicit > implicit, especially when the consumer is an LLM reading back its own commands. The ergonomic cost (typing `--platform android` every time) is absorbed by the skill, not the human.

### Decision 4: CLI command names are underscored, platform-less

**Choice:** CLI subcommand names are the MCP tool names with the `android_` / `ios_` prefix stripped, preserving underscores. So `android_tap_by_coordinates` / `ios_tap_by_coordinates` both become `tap_by_coordinates` with `--platform` as the discriminator. `start_automation_server` / `ios_start_automation_server` become `start_automation_server`. `get_interactive_elements` / `ios_get_interactive_elements` become `get_interactive_elements`. Screenshot becomes `screenshot`.

**Alternatives considered:**
- **Keep MCP names verbatim (including `android_` / `ios_` prefix):** Redundant when `--platform` is already a flag. Forces the LLM to remember both a command *and* a platform that have to agree.
- **Hyphenated names (`tap-by-coordinates`, `get-interactive-elements`):** More shell-idiomatic, but diverges from the MCP names the user explicitly asked us to keep underscored. User preference wins.

**Rationale:** Explicit user decision. Keeping underscores means the same operation has the same *verb* across MCP and CLI; only the platform indication differs in form (prefix vs flag).

### Decision 5: Success is prose on stdout; errors are prose on stderr + granular exit code

**Choice:** On success, print the MCP tool's return string to stdout and exit `0`. On failure, print the error message to stderr and exit with one of:

```
0  success
1  generic failure (unhandled exception, automation-server crash, etc.)
2  usage error (bad flag, missing required arg, invalid direction value)
3  automation server not running / not reachable
4  device or simulator not found
5  platform not supported for this command (e.g. --platform ios on install_automation_server)
```

The prose message matches the MCP tool output as closely as possible so LLM-facing text is identical across the two facades.

**Alternatives considered:**
- **Boolean 0/1 exit codes:** Forces the LLM (or a wrapper script) to grep stderr to decide whether to retry — fragile and easy to get wrong.
- **Machine-readable structured output by default:** Would duplicate the MCP prose format which is already LLM-friendly. Structured output is deferred to a later `--json` mode.

**Rationale:** Granular codes are cheap to implement (a `CliExit` exception carrying a code + message, thrown from the shared suspend functions or mapped at the CLI boundary) and immediately useful: a skill can script "retry after starting the server" specifically on exit `3`, with no string parsing.

### Decision 6: Include a reference skill in the repo

**Choice:** Ship `.claude/skills/visiontest-mobile/SKILL.md` alongside the CLI. It teaches an LLM the standard loop: `start_automation_server` → `screenshot` → `get_interactive_elements` → `tap_by_coordinates` → repeat. It documents the exit-code contract so the agent knows when to recover versus report.

**Alternatives considered:**
- **Leave skill authoring to users:** Possible, but leaves the CLI's intended consumer (LLM via skill) undocumented and slows adoption.
- **Put the skill only in docs/:** Skills live in `.claude/skills/` by convention; putting it there makes it usable on this repo itself (dogfooding) and works as a copy-paste template.

**Rationale:** The CLI's primary design audience is "an LLM using this via a skill". Shipping the skill is how we validate that the CLI actually serves that use case.

### Decision 7: Defer `--json`, daemon mode, and full tool parity to later changes

**Choice:** MVP = 13 commands, prose output, fresh JVM per invocation. `--json`, a `visiontestd` daemon, `find_element`, `swipe`, `swipe_on_element`, `list_apps`, `info_app`, and `available_device` are out of scope.

**Rationale:** Ship the minimum useful surface first, then iterate based on real skill-authoring experience. Each of the deferred items has a clear later change in the pipeline if friction shows up.

## Risks / Trade-offs

- **Handler-extraction refactor touches 30+ tools.** Low risk technically (mechanical), but a large-ish diff. Mitigated by keeping existing MCP tests green throughout (no behavior change on the MCP side).
- **New CLI dependency (clikt).** Well-maintained, pure-JVM, ~400 KB in the fat JAR. Acceptable cost. Alternative was picocli (also good); clikt picked for Kotlin idiomatic DSL.
- **~1–2 s JVM cold start per CLI command.** Noticeable if a skill issues many commands back-to-back, but acceptable for MVP. The on-device Android server persists across invocations, so only the first call in a session pays the server-start cost. If latency becomes painful, Decision 7 keeps daemon mode as an obvious follow-up.
- **Platform flag on every command is verbose.** Mitigated by the skill doing the typing; not a human-facing concern. Keeps the LLM's commands self-documenting when written into a transcript.
- **`visiontest` with no args stays MCP stdio forever.** Means we can never repurpose the no-arg form for a CLI help screen. Acceptable — `visiontest --help` still works, and the no-arg behavior is load-bearing for existing MCP launchers.
