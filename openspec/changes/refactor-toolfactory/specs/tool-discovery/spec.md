## ADDED Requirements

### Requirement: ToolDiscovery class encapsulates all path resolution
A `ToolDiscovery` class in the `discovery/` package SHALL encapsulate all asset discovery logic previously embedded in `ToolFactory`. It SHALL accept only a `Logger` as a constructor parameter.

#### Scenario: ToolDiscovery is independently constructable
- **WHEN** `ToolDiscovery(logger)` is constructed
- **THEN** it SHALL be ready to use without requiring `DeviceConfig`, `AutomationClient`, or any other `ToolFactory` dependency

### Requirement: Android APK discovery
`ToolDiscovery` SHALL provide `findAutomationServerApk()` and its testable overload `findAutomationServerApk(envApkPath, searchRoots, installDir)` with identical behavior to the current `ToolFactory` implementation.

#### Scenario: APK found via environment variable
- **WHEN** `VISION_TEST_APK_PATH` environment variable points to an existing file
- **THEN** `findAutomationServerApk()` SHALL return that file's absolute path

#### Scenario: APK found via search roots
- **WHEN** no environment variable is set and the APK exists at `<searchRoot>/automation-server/build/outputs/apk/androidTest/debug/automation-server-debug-androidTest.apk`
- **THEN** `findAutomationServerApk()` SHALL return the first match found across search roots

#### Scenario: APK found in install directory
- **WHEN** no environment variable is set and no search root contains the APK, but `<installDir>/automation-server-test.apk` exists
- **THEN** `findAutomationServerApk()` SHALL return the install directory APK path

#### Scenario: No APK found
- **WHEN** no APK is found in any location
- **THEN** `findAutomationServerApk()` SHALL return null

### Requirement: Main APK resolution from test APK path
`ToolDiscovery` SHALL provide `resolveMainApkPath(testApkPath)` with identical behavior to the current `ToolFactory` implementation.

#### Scenario: Gradle layout derivation
- **WHEN** test APK path contains `androidTest/` and `-androidTest` substrings and the derived main APK exists
- **THEN** `resolveMainApkPath()` SHALL return the derived path

#### Scenario: Install directory sibling lookup
- **WHEN** the test APK is named `automation-server-test.apk` and `automation-server.apk` exists in the same directory
- **THEN** `resolveMainApkPath()` SHALL return the sibling APK path

#### Scenario: No main APK found
- **WHEN** no derivation or sibling lookup succeeds
- **THEN** `resolveMainApkPath()` SHALL return null

### Requirement: Xcode project discovery
`ToolDiscovery` SHALL provide `findXcodeProject()` with identical cascading search behavior: environment variable → CWD → project root → code source root.

#### Scenario: Xcode project from environment variable
- **WHEN** `VISION_TEST_IOS_PROJECT_PATH` environment variable points to a valid `.xcodeproj` directory
- **THEN** `findXcodeProject()` SHALL return its absolute path

#### Scenario: Xcode project from project root
- **WHEN** no environment variable is set and the `.xcodeproj` exists relative to the detected project root
- **THEN** `findXcodeProject()` SHALL return its absolute path

#### Scenario: No Xcode project found
- **WHEN** no `.xcodeproj` is found in any location
- **THEN** `findXcodeProject()` SHALL return null

### Requirement: xctestrun bundle discovery
`ToolDiscovery` SHALL provide `findXctestrun()` and its testable overload `findXctestrun(installDir)` with identical behavior.

#### Scenario: xctestrun found in install directory
- **WHEN** `<installDir>/ios-automation-server/` contains `.xctestrun` files
- **THEN** `findXctestrun()` SHALL return the absolute path of the first file alphabetically

#### Scenario: No xctestrun found
- **WHEN** the bundle directory does not exist or contains no `.xctestrun` files
- **THEN** `findXctestrun()` SHALL return null

### Requirement: Project root discovery
`ToolDiscovery` SHALL provide `findProjectRoot(startFrom)` that walks up the directory tree (max 10 levels) looking for `settings.gradle.kts` or `settings.gradle`.

#### Scenario: Project root found
- **WHEN** a `settings.gradle.kts` or `settings.gradle` file exists within 10 parent directories of `startFrom`
- **THEN** `findProjectRoot()` SHALL return the directory containing it

#### Scenario: Project root not found within depth limit
- **WHEN** no settings file exists within 10 levels up
- **THEN** `findProjectRoot()` SHALL return null

#### Scenario: Trailing dot in path handled
- **WHEN** `startFrom` path ends with `.`
- **THEN** `findProjectRoot()` SHALL resolve the parent correctly and search normally

### Requirement: Install directory resolution
`ToolDiscovery` SHALL provide `resolveInstallDir()` with the cascading resolution: `VISIONTEST_DIR` env var → JAR directory → `~/.local/share/visiontest` default.

#### Scenario: Install dir from environment variable
- **WHEN** `VISIONTEST_DIR` environment variable is set and non-empty
- **THEN** `resolveInstallDir()` SHALL return a `File` pointing to that path

#### Scenario: Install dir default fallback
- **WHEN** no environment variable is set and not running from a JAR
- **THEN** `resolveInstallDir()` SHALL return `~/.local/share/visiontest`
