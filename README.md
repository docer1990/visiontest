# VisionTest

VisionTest lets coding agents test mobile apps end to end from the command line.
An agent can inspect an Android app or iOS Simulator, find a control, interact
with it, and verify the expected result. Structured UI results, screenshots and
flow recordings provide evidence for feature checks and bug reproduction.

Use the CLI with the project-local VisionTest skill to guide the agent through
setup, selector discovery, actions and outcome checks. MCP is also supported
for users who prefer a connected tool interface.

## Requirements

- JDK 17 or newer, on macOS or Linux (arm64 or x86_64).
- Android Platform Tools and an authorized Android device or emulator for Android.
- macOS, full Xcode and a compatible simulator runtime for iOS. Physical iOS
  devices are not supported. Prebuilt iOS test bundles are available on macOS arm64.
- An application built and installed using its own repository's instructions.

VisionTest handles UI testing and its automation server. Your application's
AGENTS.md or equivalent remains the authority for builds, artifacts, variants and
installation. The skill documents native ADB/simctl commands for lifecycle setup.

## Install and prepare your agent

Install the latest release:

```bash
curl -fsSL https://github.com/docer1990/visiontest/releases/latest/download/install.sh | bash
```

The installer verifies downloads and installs the launcher under `~/.local/bin`.
It installs the JAR and Android APK pair, plus the prebuilt iOS bundle on macOS
arm64. Add `~/.local/bin` to PATH if needed. See the
[installation guide](docs/installation.md) for platform setup, custom paths and
the iOS source fallback.

In the application repository, check the installed version and install the skill
for the agents you use:

```bash
visiontest --version
visiontest init --agent claude,opencode,codex
```

`init` writes self-contained instructions for Claude Code, OpenCode and Codex.
It works offline and does not configure MCP. After upgrading VisionTest, rerun
`init` in each project to replace the selected skill files with the instructions
embedded in that version. Local edits to those generated files are overwritten.

Ask your agent for a concrete check, for example:

> Test that continuing from the welcome screen opens Home. Follow this project's
> build instructions, verify the result, and report the evidence.

## A first CLI test

This Android example assumes the app is already installed. Replace the app ID,
selector and expected text with values from your app. Check each command's result
before continuing. Install the automation APK pair on first use or after an update;
reuse a healthy server on subsequent tests.

```bash
visiontest available_device -p android --json
visiontest install_automation_server -p android
visiontest start_automation_server -p android
visiontest launch_app -p android com.example.app
visiontest get_interactive_elements -p android --compact
```

Use the discovered selector to act, then wait for the expected result. Once both
steps are known, run them together:

```bash
visiontest flow -p android --record --json \
  --step 'tap_on_element --resource-id com.example.app:id/continue' \
  --step 'wait_for_element --text "Home" --timeout 10000'
```

Inspect the report's `success`, step results and recording status/path. The tap
waits for an actionable control; the explicit wait checks that Home appeared.
Neither a successful tap nor a saved video alone proves the app behaved correctly.
Use `find_element --json` for an immediate functional check, or `wait_for_element
--json` to await a change. No fixed sleep or manual polling loop is needed.

Use `--record` routinely for test flows to retain video evidence. It remains an
explicit flag and can fail the flow if recording fails, including after successful
UI steps. Report recorder limitations or incompatible time budgets rather than
silently dropping evidence or replaying completed actions. You do not need routine
video analysis or screenshots to confirm states already verified by structured
queries. Use and inspect screenshots for visual assertions or explicit image requests.
See [flows and recording](docs/cli-flow.md) for supported steps and time limits.

At the end of the testing task, stop the server only if you started it:

```bash
visiontest stop_automation_server -p android
```

For iOS, use `-p ios`, an installed simulator app and the iOS automation bundle;
`install_automation_server` is Android-only. Scope element commands to the app
with `--bundle-id com.example.app`. Flow steps need their own app scope; an outer
`--bundle-id` scopes observations. See the [iOS setup guide](docs/installation.md)
and [CLI examples](docs/cli-usage.md).

## How the skill guides testing

The skill reuses verified setup and selectors, discovers unfamiliar controls with
compact inspection, batches known actions, and uses built-in waits. It prefers
structured queries for functional outcomes and reserves images for information
those queries cannot establish. This avoids prescribing repeated discovery and
redundant observations; it is not a claim of measured token or reasoning savings.
See the [compact inspection evaluation](docs/benchmarks/compact-inspection.md)
and [performance tracing guide](docs/performance.md) for measurement methods.
The [guidance trials](docs/benchmarks/cli-agent-guidance.md) record simulated
command choices and their measurement limits.

Agents use CLI by default, including when MCP is available, and use MCP only on
explicit request. They follow the application's build/install instructions and
use native lifecycle commands for deep links, termination and scenario-specific
resets. Deep links must not skip navigation that is itself under test. iOS
uninstall/reinstall does not guarantee removal of Keychain, shared or remote state.

VisionTest currently assumes one selected target. There is no CLI device-selection
flag, and a running backend does not expose its identity for cross-checking against
native commands or recording. Configure one intended target consistently.

## Output and command reference

Device commands require `--platform android` or `--platform ios` (`-p`). `init`,
root `--help` and root `--version` do not. Run `visiontest --help` for commands.

Prefer `--json` where supported and `--compact` for selector discovery. JSON
stdout contains one object without JSON-RPC framing. Diagnostics go to stderr;
routine INFO logs are suppressed by default. Do not filter with `grep -v INFO`
or discard stderr. Check both the exit status and result fields. Standalone
commands can return `success:false` or an operation `error` with exit 0;
flows return nonzero for those failures. An action timeout may leave its outcome
uncertain, so inspect the app before retrying.

- [Interactions, waits, gestures and exit codes](docs/cli-usage.md)
- [JSON schemas and compact inspection](docs/cli-json.md)
- [Sequential flows, evidence and recording](docs/cli-flow.md)
- [Installation and platform setup](docs/installation.md)

## MCP alternative

MCP exposes supported automation operations to clients that prefer tool discovery.
The flow batching and recording commands described above are CLI features.

Connect VisionTest to an MCP client:

```bash
# Claude Code
claude mcp add visiontest java -- -jar ~/.local/share/visiontest/visiontest.jar

# OpenAI Codex CLI
codex mcp add visiontest -- java -jar ~/.local/share/visiontest/visiontest.jar
```

<details>
<summary>Configuration for Claude Desktop, Copilot CLI, and OpenCode</summary>

JSON configuration files require an absolute JAR path; they do not expand `~`.

Claude Desktop uses `~/Library/Application Support/Claude/claude_desktop_config.json`
on macOS or `~/.config/Claude/claude_desktop_config.json` on Linux:

```json
{
  "mcpServers": {
    "visiontest": {
      "command": "java",
      "args": ["-jar", "/ABSOLUTE/PATH/TO/.local/share/visiontest/visiontest.jar"]
    }
  }
}
```

GitHub Copilot CLI uses `~/.copilot/mcp-config.json` and the same server object
with `"type": "stdio"`. OpenCode uses this entry in a project or user
`opencode.json`:

```json
{
  "mcp": {
    "visiontest": {
      "type": "local",
      "command": ["java", "-jar", "/ABSOLUTE/PATH/TO/.local/share/visiontest/visiontest.jar"]
    }
  }
}
```

</details>

For MCP, launch the JAR without CLI arguments (or with `serve`). Keep stdout
reserved for the protocol. Use discovered tool schemas instead of CLI flags.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `VISION_TEST_LOG_LEVEL` | `PRODUCTION` | Error detail: `PRODUCTION`, `DEVELOPMENT`, or `DEBUG` |
| `VISION_TEST_APK_PATH` | auto-detected | Explicit Android test APK path |
| `VISION_TEST_IOS_PROJECT_PATH` | auto-detected | Explicit iOS `.xcodeproj` path |
| `VISIONTEST_DIR` | `~/.local/share/visiontest` | Install directory under `$HOME` |

Android uses port 9008 with automatic ADB forwarding. iOS uses port 9009 on the
simulator host network.

## Reference

- [CLI contract](docs/agentico/specs/cli.md)
- [JSON output](docs/cli-json.md)
- [Tests and performance benchmark commands](benchmarks/README.md)
- [Local performance tracing](docs/performance.md)
- [Command benchmarks and baseline collection](docs/benchmarks/http-baseline.md)
- [Element waits](docs/agentico/specs/element-waits.md)
- [Element swipe](docs/agentico/specs/element-swipe.md)
- [Missing interactions](docs/agentico/specs/missing-interactions.md)
- [Screenshots](docs/agentico/specs/screenshots.md)
- [Release process](docs/release.md)
- [0.2.0 release notes](docs/releases/0.2.0.md)
- [Contributing](CONTRIBUTING.md)
- [Open issues](https://github.com/docer1990/visiontest/issues)

## License

VisionTest is available under the [MIT License](LICENSE).
