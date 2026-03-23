## 1. Foundation — DSL and Interface

- [x] 1.1 Create `app/src/main/kotlin/com/example/visiontest/tools/ToolDsl.kt` with `ToolScope` class: wraps `Server.addTool()` with `runBlocking`/`withTimeout`/try-catch/`ErrorHandler.handleToolError()`/`CallToolResult` wrapping. Accepts `server`, `logger`, `defaultTimeoutMs` in constructor. Provides `tool(name, description, inputSchema, timeoutMs, handler)` function.
- [x] 1.2 Add `CallToolRequest` extension helpers in `ToolDsl.kt`: `requireString(key)`, `requireInt(key)`, `optionalString(key)`, `optionalInt(key)`. `require*` throws `IllegalArgumentException` on missing/invalid values. `optional*` returns null.
- [x] 1.3 Create `app/src/main/kotlin/com/example/visiontest/tools/ToolRegistrar.kt` with `interface ToolRegistrar { fun registerTools(scope: ToolScope) }`.

## 2. Extract Helpers

- [x] 2.1 Create `app/src/main/kotlin/com/example/visiontest/tools/ToolHelpers.kt` with `object ToolHelpers` containing `extractProperty()`, `extractPattern()`, `formatAppInfo()` — exact same logic as current `ToolFactory`.
- [x] 2.2 Update `app/src/test/kotlin/com/example/visiontest/ToolFactoryHelpersTest.kt`: change to test `ToolHelpers` directly, remove the stub `DeviceConfig` and `ToolFactory` instantiation.

## 3. Extract Discovery

- [ ] 3.1 Create `app/src/main/kotlin/com/example/visiontest/discovery/ToolDiscovery.kt` with `class ToolDiscovery(private val logger: Logger)`. Move functions: `findAutomationServerApk()` (both overloads), `resolveMainApkPath()`, `findXcodeProject()`, `isValidXcodeProjectPath()`, `findXctestrun()` (both overloads), `findProjectRoot()`, `findCodeSourceRoot()`, `resolveInstallDir()`, `findJarDirectory()`.
- [ ] 3.2 Update `app/src/test/kotlin/com/example/visiontest/ToolFactoryPathTest.kt`: change to test `ToolDiscovery` directly, remove mock `DeviceConfig` and `ToolFactory` instantiation. Keep all existing test cases.

## 4. Android Registrars

- [ ] 4.1 Create `app/src/main/kotlin/com/example/visiontest/tools/AndroidDeviceToolRegistrar.kt` implementing `ToolRegistrar`. Constructor takes `DeviceConfig`. Register 4 tools: `available_device_android`, `list_apps_android`, `info_app_android`, `launch_app_android`. Use `ToolScope.tool()` DSL and `ToolHelpers` for property extraction/formatting.
- [ ] 4.2 Create `app/src/main/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrar.kt` implementing `ToolRegistrar`. Constructor takes `DeviceConfig`, `AutomationClient`, `ToolDiscovery`. Register 14 tools: `install_automation_server`, `start_automation_server`, `automation_server_status`, `get_ui_hierarchy`, `find_element`, `android_tap_by_coordinates`, `android_swipe`, `android_swipe_direction`, `android_swipe_on_element`, `android_press_back`, `android_press_home`, `android_input_text`, `android_get_device_info`, `get_interactive_elements`. Use `ToolScope.tool()` DSL and request extension helpers.

## 5. iOS Registrars

- [ ] 5.1 Create `app/src/main/kotlin/com/example/visiontest/tools/IOSDeviceToolRegistrar.kt` implementing `ToolRegistrar`. Constructor takes `DeviceConfig`. Register 4 tools: `ios_available_device`, `ios_list_apps`, `ios_info_app`, `ios_launch_app`. Use `ToolScope.tool()` DSL.
- [ ] 5.2 Create `app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt` implementing `ToolRegistrar`. Constructor takes `DeviceConfig`, `IOSAutomationClient`, `ToolDiscovery`. Move `@Volatile iosXcodebuildProcess`, `buildXcodebuildCommand()`, `shellQuote()`, `startAndPollServer()`, `ServerPollResult` into this class. Register 12 tools: `ios_start_automation_server`, `ios_automation_server_status`, `ios_get_ui_hierarchy`, `ios_get_interactive_elements`, `ios_tap_by_coordinates`, `ios_swipe`, `ios_swipe_direction`, `ios_find_element`, `ios_get_device_info`, `ios_press_home`, `ios_input_text`, `ios_stop_automation_server`. Use `ToolScope.tool()` DSL.

## 6. Wire Up and Replace

- [ ] 6.1 Replace `ToolFactory.kt` content with thin coordinator: constructor stays the same, creates `ToolDiscovery` and 4 registrars, `registerAllTools()` creates `ToolScope` and delegates. Remove all tool registration methods, helpers, and discovery functions.
- [ ] 6.2 Verify `Main.kt` requires zero changes — `ToolFactory(android, ios, logger).registerAllTools(server)` still compiles.

## 7. Verify and Document

- [ ] 7.1 Run `./gradlew test` — all existing tests must pass with updated imports.
- [ ] 7.2 Run `./gradlew build` — full build must succeed with no warnings related to the refactor.
- [ ] 7.3 Update `CLAUDE.md` Architecture Overview section to reflect the new `tools/` and `discovery/` packages and file structure.
- [ ] 7.4 Update `CLAUDE.md` Unit Tests section to reflect the renamed/relocated test files.
