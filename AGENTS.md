# VisionTest Repository Guide

This file guides coding agents that modify the VisionTest repository. Product
usage belongs in `README.md`; the skill installed into other projects by
`visiontest init` is sourced from
`app/src/main/resources/agent-instructions.md`.

## Before changing code

- Read `kotlin-mcp-server.instruction.md` before modifying Kotlin MCP or CLI
  code under `app/src/`.
- Read the relevant behavioral contract under `docs/agentico/specs/`.
- Preserve public behavior unless the task explicitly changes it, and update the
  contract and user documentation when it does.

## Architecture

VisionTest has three runtime components:

- `app/`: Kotlin/JVM MCP server and CLI
- `automation-server/`: Android UIAutomator instrumentation server
- `ios-automation-server/`: iOS XCUITest automation server

Both native servers expose health and JSON-RPC endpoints. MCP tools and CLI
commands must share registrar operations rather than duplicate business logic.
Keep platform clients responsible for transport and registrars responsible for
validation and user-facing operation results.

## Public contracts

- Device CLI commands require `--platform android` or `--platform ios`;
  `init`, root `--help`, and root `--version` do not.
- Android-only commands must reject iOS with exit code 5.
- Validate CLI arguments before creating device components or contacting a
  backend.
- Preserve the documented exit codes and the distinction between thrown
  failures and normally returned operation failures.
- Structured CLI output must contain one JSON object without JSON-RPC framing.
- Update `McpStdioE2ETest.EXPECTED_TOOLS` whenever the MCP tool set changes.
- Changes to native iOS JSON-RPC methods require matching Swift tests, Kotlin
  client tests, and compatibility guidance for installed bundles.
- Keep `app/src/main/resources/agent-instructions.md` self-contained because it
  is copied into unrelated repositories. Do not add repository-relative links.

## Verification

Use the narrowest relevant test while iterating, then run the full gate before
opening a pull request:

```bash
./gradlew :app:test
./gradlew :app:e2eTest
./gradlew build
```

For native iOS changes, also run:

```bash
xcodebuild test \
  -project ios-automation-server/IOSAutomationServer.xcodeproj \
  -scheme IOSAutomationServer \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:IOSAutomationServerTests
```

Do not lower coverage floors, regenerate detekt baselines, or refresh lint
baselines to make a change pass. Fix new findings and keep
`git diff --check` clean.

## Documentation ownership

- `README.md`: product value, installation, first use, and navigation
- `AGENTS.md`: repository instructions for coding agents
- `CLAUDE.md`: detailed Claude Code development context
- `CONTRIBUTING.md`: contributor setup, architecture, testing, and extension
- `app/src/main/resources/agent-instructions.md`: distributable VisionTest skill
- `docs/agentico/specs/`: externally observable behavior
- `docs/decisions/`: durable architectural choices
