## ADDED Requirements

### Requirement: Pre-built test bundle discovery
The MCP server SHALL discover pre-built iOS test bundles by searching for `.xctestrun` files in the install directory (`~/.local/share/visiontest/ios-automation-server/`).

#### Scenario: xctestrun found in install directory
- **WHEN** a `.xctestrun` file exists in the install directory's `ios-automation-server/` subdirectory
- **THEN** `findXctestrun()` returns the path to the `.xctestrun` file

#### Scenario: No xctestrun in install directory
- **WHEN** no `.xctestrun` file exists in the install directory
- **THEN** `findXctestrun()` returns null

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

### Requirement: Destination override for pre-built bundle
When launching with `test-without-building`, the tool SHALL pass the `-destination` flag with the detected simulator name to override the baked-in destination.

#### Scenario: Simulator destination passed correctly
- **WHEN** the pre-built path is used and a simulator named "iPhone 16" is booted
- **THEN** the xcodebuild command includes `-destination 'platform=iOS Simulator,name=iPhone 16'`
