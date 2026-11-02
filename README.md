# VisionTest

Building a mobile app means moving constantly between code and the app itself.
You change a screen, open it on a device, try an interaction, and notice something
that sends you back to the editor. That feedback shapes the next change.

Working with a coding agent adds another participant to this process. The agent
can help implement a feature or investigate a bug, and access to the running app
gives it more context to work with. A reproducible interaction, an observed UI
state, or a screenshot can make the next conversation more concrete.

VisionTest grew out of a desire to make that feedback easier to obtain and share.
It lets developers and coding agents inspect Android apps and iOS simulators,
exercise specific flows, and collect evidence about what happened.

The developer guides the work, chooses what matters, and evaluates the experience.
VisionTest supports that process with checks that can be repeated as the app changes.

## The thinking behind it

Mobile automation already has established tools and frameworks. VisionTest
focuses on the checks a developer and a coding agent repeat while changing an
app. In that workflow, UI observations become model input, extra interactions
add latency, and unreliable actions can send the agent through repeated attempts.
Token consumption, execution time, and flakiness are central design constraints.

Three priorities guide development.

- Keep token consumption focused on the task. Compact, filtered UI results and
  targeted queries give the agent relevant information. The skill encourages
  reusing known selectors and reserves screenshots for questions that need images.
- Keep execution time focused on useful work. Flows batch known actions in one
  CLI invocation. The skill reuses verified setup and avoids redundant discovery
  and readiness checks. Built-in waits respond to UI conditions instead of
  requiring fixed sleeps.
- Make checks repeatable. Stable selectors, actionability waits, and explicit
  outcome checks address sources of UI-test flakiness. Structured results expose
  failures, and an uncertain action outcome must be inspected before retrying.

VisionTest builds on Android's UIAutomator through instrumentation and Apple's
XCUITest. These native test frameworks expose UI elements and accessibility
properties. VisionTest combines that access with structured results and an
agent workflow designed around these priorities.

The main development goal is to make everyday checks more reliable and less
expensive to repeat. Improvements to token usage, execution time, and flakiness
take priority over growing the feature list. New features should solve a concrete
testing need and justify the execution and context costs they introduce.

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

Device commands automatically start and reuse a local Kotlin daemon. Each invocation
is a fresh client; the host retains connections and serializes whole commands and
flows. Use `visiontest --standalone COMMAND ...` to execute in the caller JVM.
`visiontest daemon status --json`, `daemon start`, and `daemon stop` need no platform.
The daemon exits after 15 idle minutes. After upgrading, stop the old host and retry
explicitly; incompatible builds are never replaced silently.

Use exactly one usable Android device or one booted iOS simulator. The daemon checks
inventory for each operation; external MCP and standalone clients must also be
serialized by the caller. Caller paths, environment, stdin and tracing remain
request-specific. A lost response may mean a mutation completed: inspect the app
state before continuing and never replay automatically. When native completion is
uncertain, explicitly restart the native backend and daemon before more mutations.
See [daemon behavior and measured results](docs/benchmarks/daemon.md).

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

If the complete requested scenario is Continue opening Home, and its starting
state, selector and expected result are known, record it even on its first execution.
Before every recorded test, close the app on the established device:

```bash
adb -s "SERIAL" shell am force-stop com.example.app
```

Inspect the result, then relaunch separately:

```bash
visiontest launch_app -p android com.example.app
```

Check the launch result before running the recorded flow. This example assumes the
app opens on the welcome screen; include any required navigation in the flow:

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
This also applies after app launch or a swipe. Inspect launch diagnostics before
continuing; await a known ready selector or immediately discover controls if none
is known. Selector taps and targeted input already wait for actionability.

During exploration, batch known actions with `flow --json` without `--record` and
inspect structured UI data at unknown branches. Start recording only when the
complete scenario, starting state, selectors, data and assertions are defined and
that requested test still needs execution. Use one recorded flow for the scenario,
not a video per exploratory segment. Termination and relaunch are required before
each recorded flow, even if the app is already on the correct screen. Unrecorded
exploration does not require restarting the app. Keep the healthy automation server
running; restarting the app does not clear its persisted data.
Recording remains an explicit CLI flag and
can fail the flow even after successful UI steps. Report user opt-out or concrete
recorder, flow or budget limitations before omitting recording from a ready test.
Do not replay completed checks just to obtain video or routinely analyze recordings.

Use structured CLI results as functional evidence without per-step screenshots.
Capture screenshots only to document an observed app bug or an assertion the CLI
queries cannot resolve, such as pixel appearance or occlusion. State the reason
for capture and inspect the image before visual claims. A generic request for
evidence or a "visual verification" label does not require a screenshot when the
structured results answer the assertion.
See [flows and recording](docs/cli-flow.md) for supported steps and time limits.

At the end of the testing task, wait for the last flow to return, inspect its
results and finalized recording, and collect any needed bug evidence. If the app
should be closed and no pending test needs its state, terminate it with the
established device identifier, for example on Android:

```bash
adb -s "SERIAL" shell am force-stop com.example.app
```

On iOS, the equivalent is `xcrun simctl terminate "UDID" "com.example.app"`.
These are native commands outside flow. Inspect their exit status and diagnostics;
do not close the app while recording finalizes. Finish evidence collection before
the mandatory restart for the next recorded test; exploratory flows may reuse state.
Stop the automation server only if you started it:

```bash
visiontest stop_automation_server -p android
```

For iOS, use `-p ios`, an installed simulator app and the iOS automation bundle;
`install_automation_server` is Android-only. Scope element commands to the app
with `--bundle-id com.example.app`. Flow steps need their own app scope; an outer
`--bundle-id` scopes observations. See the [iOS setup guide](docs/installation.md)
and [CLI examples](docs/cli-usage.md).

## How the skill guides testing

The project-local skill translates these principles into CLI instructions for
the agent. It follows the application's build/install instructions and uses
native lifecycle commands for deep links, termination and scenario-specific
resets. Deep links must not skip navigation under test. iOS uninstall/reinstall
does not guarantee removal of Keychain, shared or remote state.

The [compact inspection evaluation](docs/benchmarks/compact-inspection.md),
[performance tracing guide](docs/performance.md), and
[guidance trials](docs/benchmarks/cli-agent-guidance.md) document measurements,
methods and limitations. Simulated command choices and smaller payloads alone
do not establish token savings or faster end-to-end execution.

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
CLI commands and MCP tools call shared operation implementations. The flow
batching and recording commands described above are CLI features.

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
- [1.0.1 release notes](docs/releases/1.0.1.md)
- [Contributing](CONTRIBUTING.md)
- [Open issues](https://github.com/docer1990/visiontest/issues)

## License

VisionTest is available under the [MIT License](LICENSE).
