## 1. Scaffolding & dependency

- [x] 1.1 Add `com.github.ajalt.clikt:clikt:4.4.0` (or latest 4.x) to `app/build.gradle.kts` dependencies
- [x] 1.2 Create package `app/src/main/kotlin/com/example/visiontest/cli/` with a placeholder `VisionTestCli.kt` (empty clikt `NoOpCliktCommand` root) to anchor future subcommands
- [x] 1.3 Update `Main.kt` to branch on `args`: if `args.isEmpty()` or `args[0] == "serve"`, run the existing MCP stdio flow (unchanged); otherwise construct `VisionTestCli()` and call its `main(args)`. Preserve the existing logger setup and shutdown hook in both branches
- [x] 1.4 Add a unit test `app/src/test/kotlin/com/example/visiontest/MainDispatchTest.kt` covering (a) empty args keeps MCP path reachable, (b) `serve` keeps MCP path reachable, (c) arbitrary first-arg routes to CLI (mock/fake the two paths so the test doesn't actually start an MCP server)

## 2. Handler body extraction refactor

Goal: each tool's body becomes an `internal suspend` function taking typed parameters. MCP registration shrinks to arg extraction + delegation. The CLI calls the same functions. No MCP behavior change.

- [x] 2.1 `AndroidDeviceToolRegistrar` — extract body of each tool (`available_device_android`, `list_apps_android`, `info_app_android`, `launch_app_android`) into `internal suspend fun` methods on the registrar. Update MCP registrations to delegate. Keep arg extraction in the MCP `scope.tool { ... }` block
- [x] 2.2 `IOSDeviceToolRegistrar` — same treatment for `ios_available_device`, `ios_list_apps`, `ios_info_app`, `ios_launch_app`
- [x] 2.3 `AndroidAutomationToolRegistrar` — extract bodies of every `registerXxx` tool into `internal suspend fun` methods. The existing `captureScreenshot`, `resolveScreenshotPath`, `writeScreenshot` helpers already follow this pattern; generalise across all 15 Android automation tools
- [x] 2.4 `IOSAutomationToolRegistrar` — same treatment for every iOS automation tool
- [x] 2.5 Verify `./gradlew :app:test` stays green — no MCP behavior change expected, refactor is mechanical
- [x] 2.6 Add targeted tests (or expand existing ones) that exercise the extracted functions directly without going through `ToolScope`, to lock in their call shape as a stable public-within-the-module surface

## 3. CLI root dispatcher & exit-code infrastructure

- [x] 3.1 In `cli/VisionTestCli.kt`, define the root `VisionTestCli` as a clikt `NoOpCliktCommand` with `subcommands(...)` for all 13 MVP commands (stubs OK at this stage)
- [x] 3.2 Create `cli/CliExit.kt` with a `CliExit(code: Int, message: String) : Exception` type and a sealed enum of the six exit codes (`Success=0`, `GenericFailure=1`, `UsageError=2`, `ServerNotReachable=3`, `DeviceNotFound=4`, `PlatformNotSupported=5`). Document each code's meaning in a KDoc block
- [x] 3.3 Create `cli/CliErrorHandler.kt` with a `runCliCommand(block: suspend () -> String)` helper that: calls `block()`, prints the result to stdout, exits `0` on success; catches `CliExit` → prints to stderr + `exitProcess(code)`; catches `IllegalArgumentException` / clikt usage errors → stderr + `exitProcess(2)`; catches other exceptions → stderr + `exitProcess(1)`
- [x] 3.4 Create `cli/PlatformOption.kt` with a reusable clikt option definition: `--platform` / `-p`, required, `choice("android", "ios")`. Android-only commands override the `choice` to just `"android"` and print `"This command is Android-only"` → exit 5 if the user tries `ios`
- [x] 3.5 Create `cli/ComponentHolder.kt` (or equivalent) — a minimal object graph the CLI can instantiate per invocation to get `Android`, `IOSManager`, `AutomationClient`, `IOSAutomationClient`, and the four registrars without duplicating `Main.kt`'s wiring. Ensure it respects `AppConfig.createDefault()` the same way the MCP path does, and registers the same shutdown hook behavior (close `android` and `ios` on JVM exit)

## 4. CLI subcommands (one task per command)

Each subcommand lives in its own file under `cli/commands/`. Every command parses typed args via clikt, obtains its backing function via `ComponentHolder`, and calls it through `runCliCommand`. The function body is the extracted `internal suspend fun` from section 2.

### Setup

- [ ] 4.1 `InstallAutomationServerCommand` — `visiontest install_automation_server --platform android`. Reject `--platform ios` (exit 5). Delegates to `AndroidAutomationToolRegistrar.installAutomationServer()`
- [ ] 4.2 `StartAutomationServerCommand` — `visiontest start_automation_server --platform android|ios`. Delegates to the platform's `startAutomationServer()` extracted function. Timeout mirrors the MCP tool's (30 s Android, 200 s iOS)
- [ ] 4.3 `AutomationServerStatusCommand` — `visiontest automation_server_status --platform android|ios`. Delegates to the platform's `automationServerStatus()` function

### Inspection

- [ ] 4.4 `GetInteractiveElementsCommand` — `visiontest get_interactive_elements --platform android|ios [--include-disabled]`. Delegates to `getInteractiveElements(includeDisabled: Boolean)` on the relevant registrar
- [ ] 4.5 `GetUiHierarchyCommand` — `visiontest get_ui_hierarchy --platform android|ios`. Delegates to `getUiHierarchy()`. Use 30 s timeout to match MCP
- [ ] 4.6 `GetDeviceInfoCommand` — `visiontest get_device_info --platform android|ios`. Delegates to `getDeviceInfo()`
- [ ] 4.7 `ScreenshotCommand` — `visiontest screenshot --platform android|ios [--output PATH]`. Delegates to the platform's `captureScreenshot(outputPath: String?)` (already exists on both registrars). Default path behavior is preserved (resolves `./screenshots/<platform>_screenshot_<ts>.png` against CWD). 30 s timeout

### Interaction

- [ ] 4.8 `TapByCoordinatesCommand` — `visiontest tap_by_coordinates --platform android|ios <x> <y>`. `x` and `y` are required integer positional args. Delegates to `tapByCoordinates(x, y)`
- [ ] 4.9 `InputTextCommand` — `visiontest input_text --platform android|ios <text>`. `text` is a required positional arg (single value; the skill can quote strings containing spaces). Delegates to `inputText(text)`
- [ ] 4.10 `SwipeDirectionCommand` — `visiontest swipe_direction --platform android|ios <direction> [--distance short|medium|long] [--speed slow|normal|fast]`. `direction` is a required positional from `{up,down,left,right}`. Clikt `choice(...)` validates it (invalid → exit 2). Defaults mirror MCP (distance=medium, speed=normal). Delegates to `swipeByDirection(direction, distance, speed)`

### Navigation

- [ ] 4.11 `PressBackCommand` — `visiontest press_back --platform android`. Android-only; rejects `--platform ios` → exit 5. Delegates to `pressBack()`
- [ ] 4.12 `PressHomeCommand` — `visiontest press_home --platform android|ios`. Delegates to the platform's `pressHome()`

### Apps

- [ ] 4.13 `LaunchAppCommand` — `visiontest launch_app --platform android|ios <id>`. `id` is a required positional (package name for Android, bundle ID for iOS). Delegates to the platform's launch-app function

## 5. Exit-code mapping

- [ ] 5.1 Teach extracted functions (or a thin shim in each CLI command) to throw `CliExit(ServerNotReachable, "...")` when `automationClient.isServerRunning()` returns false, replacing the MCP-side "Use 'start_automation_server' first" short-circuit. On the MCP side the string return is preserved (the extracted function can return the same string for MCP while the CLI command maps `ServerNotReachable` to exit 3 — prefer a single path: the extracted function throws `CliExit`, and the MCP registration catches it and converts back to the string form for compatibility)
- [ ] 5.2 Map `Android.getFirstAvailableDevice()` / `IOSManager.getFirstAvailableDevice()` failures to `CliExit(DeviceNotFound, "...")`
- [ ] 5.3 Map clikt `UsageError` / `MissingArgument` / `BadParameterValue` to exit 2 via clikt's built-in mechanism (`CliktError.statusCode`)
- [ ] 5.4 Any uncaught exception in `runCliCommand` → exit 1 with the exception message on stderr

## 6. Tests

- [ ] 6.1 `app/src/test/kotlin/com/example/visiontest/cli/VisionTestCliTest.kt` — table test covering (a) each command parses its required args, (b) missing args produces usage error (exit 2), (c) bad `--platform` value is rejected, (d) Android-only commands reject `--platform ios`
- [ ] 6.2 `app/src/test/kotlin/com/example/visiontest/cli/CliErrorHandlerTest.kt` — covers exit-code mapping for each `CliExit` variant, for uncaught exceptions, and for success
- [ ] 6.3 One integration-style test per CLI command using fakes for `Android` / `IOSManager` / `AutomationClient` / `IOSAutomationClient`, verifying the command delegates with the right parameters and prints the expected stdout on success
- [ ] 6.4 Ensure `./gradlew test` stays green end-to-end (app + automation-server)

## 7. Reference skill

- [ ] 7.1 Create `.claude/skills/visiontest-mobile/SKILL.md` with: one-paragraph overview; the standard loop (`start_automation_server` → `screenshot` → `get_interactive_elements` → `tap_by_coordinates` → repeat); the `--platform` flag convention; the exit-code contract (with "what to do on each code"); the Flutter `contentDescription` gotcha copied from `CLAUDE.md`; a short example session
- [ ] 7.2 If Claude Code skill metadata requires a frontmatter block, include it (name, description, when-to-use). Keep the body under 200 lines

## 8. Documentation

- [ ] 8.1 Add a "CLI Usage" section to `CLAUDE.md` listing all 13 commands with one-line descriptions, the `--platform` rule, and the exit-code table. Place it after the existing "MCP Tools" section
- [ ] 8.2 Add a short entry to `LEARNING.md` titled "Dual facade: MCP tools + CLI" explaining the handler-extraction refactor, why both facades share one implementation, and the decision to defer `--json` / daemon mode
- [ ] 8.3 Update `docs/installation.md` (if present) to mention the CLI usage alongside the MCP configuration, including that `visiontest` with no args keeps doing what it does today

## 9. Verification

- [ ] 9.1 `./gradlew build` passes
- [ ] 9.2 `./gradlew test` passes (app + automation-server)
- [ ] 9.3 `./gradlew shadowJar` produces a JAR that runs both `java -jar visiontest.jar` (MCP stdio, unchanged behavior) and `java -jar visiontest.jar <command> --platform <p> [args]` (CLI)
- [ ] 9.4 Manual smoke: with an Android emulator running, `visiontest install_automation_server --platform android && visiontest start_automation_server --platform android && visiontest screenshot --platform android` produces a PNG under `./screenshots/`
- [ ] 9.5 Manual smoke: with an iOS simulator booted, `visiontest start_automation_server --platform ios && visiontest get_interactive_elements --platform ios` returns a non-empty elements list
- [ ] 9.6 Manual smoke: `visiontest press_back --platform ios` exits with code 5 and a clear message
- [ ] 9.7 Manual smoke: `visiontest tap_by_coordinates --platform android 100` exits with code 2 and clikt's missing-argument message
- [ ] 9.8 Manual smoke: `visiontest screenshot --platform android` with the automation server stopped exits with code 3 and a message instructing the caller to run `start_automation_server`
