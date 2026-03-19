## ADDED Requirements

### Requirement: iOS project path environment variable
The `VISION_TEST_IOS_PROJECT_PATH` environment variable SHALL override all other Xcode project discovery methods when set.

#### Scenario: Env var set to valid path
- **WHEN** `VISION_TEST_IOS_PROJECT_PATH` is set to an existing `.xcodeproj` path
- **THEN** `findXcodeProject` returns that path without checking other locations

#### Scenario: Env var not set
- **WHEN** `VISION_TEST_IOS_PROJECT_PATH` is not set
- **THEN** `findXcodeProject` falls back to existing discovery (CWD, project root, code source)

### Requirement: IOSAutomationConfig constant
`IOSAutomationConfig` SHALL define `XCODE_PROJECT_PATH_ENV = "VISION_TEST_IOS_PROJECT_PATH"` as a constant.

#### Scenario: Constant available
- **WHEN** code references `IOSAutomationConfig.XCODE_PROJECT_PATH_ENV`
- **THEN** it resolves to `"VISION_TEST_IOS_PROJECT_PATH"`

### Requirement: Improved iOS error message
When the Xcode project is not found, the error message SHALL include instructions to clone the repo and set the `VISION_TEST_IOS_PROJECT_PATH` env var.

#### Scenario: Xcode project not found
- **WHEN** `findXcodeProject` returns null and `ios_start_automation_server` reports an error
- **THEN** the error message includes `git clone` and `export VISION_TEST_IOS_PROJECT_PATH=...` guidance
