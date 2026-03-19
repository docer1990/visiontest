## ADDED Requirements

### Requirement: APK discovery from install directory
The `findAutomationServerApk` function SHALL accept an optional `installDir` parameter and check for `automation-server-test.apk` in that directory as a fallback after env var and search root checks.

#### Scenario: APK found in install directory
- **WHEN** `VISION_TEST_APK_PATH` is not set AND no APK exists in Gradle build paths AND `automation-server-test.apk` exists in the install directory
- **THEN** `findAutomationServerApk` returns the path to the APK in the install directory

#### Scenario: Env var takes priority over install directory
- **WHEN** `VISION_TEST_APK_PATH` is set AND `automation-server-test.apk` exists in the install directory
- **THEN** `findAutomationServerApk` returns the env var path

#### Scenario: Search roots take priority over install directory
- **WHEN** APK exists in both a Gradle build path AND the install directory
- **THEN** `findAutomationServerApk` returns the Gradle build path

#### Scenario: Install directory is null
- **WHEN** `installDir` is null
- **THEN** the install directory fallback is skipped and the function behaves as before

### Requirement: Main APK derivation handles installed names
The `install_automation_server` tool SHALL find the main APK (`automation-server.apk`) when using APKs from the install directory, where the Gradle path string-replace derivation does not work.

#### Scenario: Main APK derived from installed test APK
- **WHEN** the test APK path is `~/.local/share/visiontest/automation-server-test.apk`
- **THEN** the tool checks for `automation-server.apk` in the same directory and finds it

#### Scenario: Main APK derived from Gradle build path
- **WHEN** the test APK path contains `androidTest/` in its path
- **THEN** the existing string-replace derivation is used (no change to current behavior)

### Requirement: Default install directory resolution
The no-arg `findAutomationServerApk()` SHALL resolve the install directory from `VISIONTEST_DIR` env var or default to `~/.local/share/visiontest`.

#### Scenario: Custom install directory via env var
- **WHEN** `VISIONTEST_DIR` is set to `/home/user/custom`
- **THEN** the install directory fallback checks `/home/user/custom/automation-server-test.apk`

#### Scenario: Default install directory
- **WHEN** `VISIONTEST_DIR` is not set
- **THEN** the install directory fallback checks `~/.local/share/visiontest/automation-server-test.apk`

### Requirement: Updated error messages
When APK is not found, error messages SHALL mention re-running `install.sh` or setting `VISION_TEST_APK_PATH`.

#### Scenario: APK not found error message
- **WHEN** `findAutomationServerApk` returns null and the tool reports an error
- **THEN** the error message includes guidance to re-run `install.sh` or set `VISION_TEST_APK_PATH`
