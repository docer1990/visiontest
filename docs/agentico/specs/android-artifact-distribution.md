# Android artifact distribution

## Purpose

Android artifact distribution builds, publishes, verifies, installs, discovers, and deploys the paired main and instrumentation APKs required by the Android automation server.

## Requirements

### Requirement: Tagged releases publish both Android APKs and checksums

For a `v*` tag, the release workflow MUST build `:automation-server:assembleDebug` and `:automation-server:assembleDebugAndroidTest`, stage their outputs as `automation-server.apk` and `automation-server-test.apk`, generate SHA-256 files for both, and publish all four files in the GitHub Release. The release job MUST depend on the configured MCP, Android, iOS test, and iOS bundle-build jobs.

#### Scenario: Release assets are staged

- **Given** all prerequisite release jobs pass
- **When** the release job stages Android outputs
- **Then** it SHALL copy the debug main APK and debug androidTest APK to the simple installed names and SHALL create matching `.sha256` files

#### Scenario: A staged asset is missing

- **Given** any required APK or checksum was not created
- **When** the workflow validates staged release files
- **Then** the release job MUST fail before publishing the GitHub Release

### Requirement: The network installer verifies both APK downloads

A normal network installation MUST download each APK and its release checksum through the shared `download_and_verify` helper, calculate SHA-256 with `sha256sum` or `shasum -a 256`, and move the artifact and checksum into the resolved install directory only after a match. Installed asset files MUST use mode 600 under the installer's restrictive umask.

#### Scenario: Both checksums match

- **Given** release downloads complete and both calculated hashes match their published values
- **When** APK installation finishes
- **Then** the install directory SHALL contain `automation-server.apk`, `automation-server.apk.sha256`, `automation-server-test.apk`, and `automation-server-test.apk.sha256`

#### Scenario: Checksum mismatch or no hash tool

- **Given** a downloaded APK hash differs or neither supported hash command is available
- **When** verification runs
- **Then** the installer MUST print a clear error, MUST exit nonzero, and MUST NOT install that unverified APK as the destination

### Requirement: APK distribution supports installer host platforms independently of device platform

The installer MUST download the Android APK pair on supported macOS and Linux hosts with x86_64 or arm64 architecture. Unsupported operating systems or CPU architectures MUST fail platform detection. The CLI `install_automation_server` operation itself MUST accept only `--platform android`; `--platform ios` MUST exit with code 5.

#### Scenario: Supported host

- **Given** a normal network install on supported macOS or Linux
- **When** host detection and Java validation pass
- **Then** both APKs SHALL be downloaded regardless of whether the host also supports the prebuilt iOS bundle

#### Scenario: Local-JAR testing mode

- **Given** `install.sh --local-jar <path>` is used
- **When** installation runs
- **Then** it SHALL install only the local JAR and SHALL skip APK and iOS bundle downloads

### Requirement: Runtime installation preserves the main/test relationship

Android `install_automation_server` MUST discover the test APK, resolve its corresponding main APK, install the main APK first with `adb install -r`, then install the test APK the same way. Installed simple names MUST be treated as siblings; Gradle output names MUST be related by their standard directory and filename transformation.

#### Scenario: Installed release pair is used

- **Given** discovery selects `<install-dir>/automation-server-test.apk` and its sibling main APK exists
- **When** device installation runs
- **Then** it SHALL install `automation-server.apk` before `automation-server-test.apk`

#### Scenario: Test or main APK cannot be found

- **Given** discovery finds no test APK or cannot resolve an existing main APK
- **When** device installation runs
- **Then** it MUST return guidance to rerun `install.sh`, set `VISION_TEST_APK_PATH`, or build the required artifacts and MUST NOT attempt to install an incomplete pair

### Requirement: Distribution failures remain observable

Network, tag-discovery, checksum, unsupported-host, and filesystem failures in `install.sh` MUST terminate installation nonzero. A thrown ADB/device failure during the CLI operation MUST pass through the shared CLI mapper; an unmapped command-execution failure uses generic exit code 1, while absence of a device maps to exit code 4.

#### Scenario: ADB installation fails

- **Given** both APK paths resolve but an `adb install -r` command fails
- **When** the CLI operation executes
- **Then** it MUST stop the operation, print the propagated error to stderr, and exit nonzero

## Verification

- Release production workflow: `.github/workflows/release.yaml`
- Installer production behavior: `install.sh`
- Runtime production discovery and installation: `app/src/main/kotlin/com/example/visiontest/discovery/ToolDiscovery.kt`, `app/src/main/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrar.kt`, `app/src/main/kotlin/com/example/visiontest/cli/commands/InstallAutomationServerCommand.kt`
- Executable discovery and CLI tests: `app/src/test/kotlin/com/example/visiontest/ToolFactoryPathTest.kt`, `app/src/test/kotlin/com/example/visiontest/cli/VisionTestCliTest.kt`, `app/src/test/kotlin/com/example/visiontest/cli/CliErrorHandlerTest.kt`
