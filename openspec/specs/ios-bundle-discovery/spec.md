## ADDED Requirements

### Requirement: Pre-built test bundle discovery
The MCP server SHALL discover pre-built iOS test bundles by searching for `.xctestrun` files in the install directory (`~/.local/share/visiontest/ios-automation-server/`).

#### Scenario: xctestrun found in install directory
- **WHEN** a `.xctestrun` file exists in the install directory's `ios-automation-server/` subdirectory
- **THEN** `findXctestrun()` returns the path to the `.xctestrun` file

#### Scenario: Single xctestrun selected when multiple exist
- **WHEN** multiple `.xctestrun` files exist in the install directory (e.g., from different SDK versions)
- **THEN** `findXctestrun()` returns the first match (glob `*.xctestrun`, sorted alphabetically)

#### Scenario: No xctestrun in install directory
- **WHEN** no `.xctestrun` file exists in the install directory
- **THEN** `findXctestrun()` returns null

#### Scenario: Install directory resolved from VISIONTEST_DIR env var
- **WHEN** `VISIONTEST_DIR` is set to a custom path
- **THEN** `findXctestrun()` searches `$VISIONTEST_DIR/ios-automation-server/` instead of the default location

### Requirement: Dual-path launch in ios_start_automation_server
The `ios_start_automation_server` tool SHALL use `xcodebuild test-without-building -xctestrun` when a pre-built bundle is found, and fall back to the existing `xcodebuild test -project` path when no pre-built bundle is available.

#### Scenario: Pre-built bundle available
- **WHEN** `findXctestrun()` returns a valid path
- **THEN** the tool launches with `xcodebuild test-without-building -xctestrun <path> -destination <simulator>`

#### Scenario: No pre-built bundle, source project available
- **WHEN** `findXctestrun()` returns null AND `findXcodeProject()` returns a valid path
- **THEN** the tool launches with `xcodebuild test -project <path>` (existing behavior)

#### Scenario: Neither pre-built bundle nor source project available
- **WHEN** both `findXctestrun()` and `findXcodeProject()` return null
- **THEN** the tool returns an error message with instructions to re-run `install.sh` on macOS or clone the repo

#### Scenario: Pre-built bundle fails, source project available as retry
- **WHEN** `findXctestrun()` returns a valid path BUT `xcodebuild test-without-building` fails (e.g., Xcode version mismatch) AND `findXcodeProject()` returns a valid path
- **THEN** the tool retries with `xcodebuild test -project <path>` (source build fallback) and logs a warning about the pre-built bundle failure

### Requirement: Destination override for pre-built bundle
When launching with `test-without-building`, the tool SHALL pass the `-destination` flag with the detected simulator name to override the baked-in destination.

#### Scenario: Simulator destination passed correctly
- **WHEN** the pre-built path is used and a simulator named "iPhone 16" is booted
- **THEN** the xcodebuild command includes `-destination 'platform=iOS Simulator,name=iPhone 16'`

### Requirement: findXctestrun uses testable overload pattern
The `findXctestrun()` implementation SHALL follow the existing private/internal split pattern (consistent with `findAutomationServerApk`) to enable unit testing without depending on env vars or filesystem state.

#### Scenario: Testable internal overload
- **GIVEN** the existing pattern where a private zero-arg method reads env vars and delegates to an internal method with explicit parameters
- **THEN** `findXctestrun()` has a private version (reads `VISIONTEST_DIR`, resolves default) and an `internal` overload accepting an explicit install directory parameter

### Requirement: Tool description updated for dual-path behavior
The `ios_start_automation_server` tool description SHALL reflect that it uses a pre-built bundle when available, falling back to source build.

#### Scenario: Updated tool description
- **WHEN** the tool is registered
- **THEN** the description indicates: "Uses pre-built test bundle if available, otherwise builds from source"
