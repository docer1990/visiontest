# iOS bundle distribution

## Purpose

iOS bundle distribution packages a portable, prebuilt XCUITest server for fast startup on compatible Apple Silicon Macs while preserving source-build startup when the prebuilt artifact cannot be used.

## Requirements

### Requirement: The release workflow builds and publishes a portable simulator bundle

For a `v*` tag, the macOS release-build job MUST run `xcodebuild build-for-testing` for the iOS Automation Server scheme with a fixed derived-data path, archive the produced `.xctestrun`, `Debug-iphonesimulator/IOSAutomationServer.app`, and `Debug-iphonesimulator/IOSAutomationServerUITests-Runner.app`, and publish `ios-automation-server.tar.gz` with `ios-automation-server.tar.gz.sha256` through the final GitHub Release.

#### Scenario: Archive is transferred between jobs

- **Given** the macOS bundle build succeeds
- **When** it finishes packaging
- **Then** it SHALL upload the archive and checksum as a workflow artifact and the dependent Linux release job SHALL download both for publication

#### Scenario: Required archive content is absent

- **Given** the xctestrun or either app bundle is missing
- **When** archive creation or final staging runs
- **Then** the workflow MUST fail rather than publish an incomplete release

### Requirement: The xctestrun must be relocatable

Before archiving, the workflow MUST convert the xctestrun plist to XML, MUST require at least one `__TESTROOT__` reference, and MUST reject any `/Users/` absolute path. The archive MUST preserve the products directory layout expected by `xcodebuild test-without-building`.

#### Scenario: Portable xctestrun

- **Given** build-for-testing produced an xctestrun using `__TESTROOT__` and no CI-user absolute path
- **When** the portability gate runs
- **Then** packaging SHALL continue with the xctestrun and its sibling `Debug-iphonesimulator` products

#### Scenario: Nonportable xctestrun

- **Given** the plist lacks `__TESTROOT__` or contains a `/Users/` path
- **When** the portability gate runs
- **Then** the macOS release-build job MUST exit nonzero

### Requirement: The installer consumes the prebuilt asset only on macOS arm64

The network installer MUST download, SHA-256 verify, and extract the prebuilt archive only when the detected host is macOS arm64. It MUST skip the bundle on Linux and macOS x86_64 with an informational message; Intel Mac guidance MUST direct users to a source build. The JAR and Android APK installation MUST remain available on all otherwise supported hosts.

#### Scenario: Apple Silicon installation

- **Given** a normal network install runs on macOS arm64
- **When** the archive checksum matches
- **Then** the extracted bundle SHALL be installed under `<install-dir>/ios-automation-server/`

#### Scenario: Linux or Intel Mac installation

- **Given** the host is Linux or macOS x86_64
- **When** iOS bundle installation is considered
- **Then** the installer SHALL skip downloading the archive and SHALL report the platform-specific reason

### Requirement: Bundle replacement rejects unsafe or incomplete extraction

The installer MUST reject archive entries with absolute paths, parent traversal, or symbolic links before replacing an existing bundle. It MUST extract into a temporary directory, move an existing bundle to a backup, move the new bundle into place, and attempt to restore the backup if the final move fails. On successful replacement it MUST remove the backup, archive, and archive checksum.

#### Scenario: Unsafe archive

- **Given** an archive contains an absolute path, parent traversal, or symbolic link
- **When** archive validation runs
- **Then** installation MUST fail before removing the existing installed bundle

#### Scenario: Replacement move fails

- **Given** a previous bundle was backed up and the new temporary bundle cannot be moved into place
- **When** replacement fails
- **Then** the installer SHALL attempt to restore the previous bundle and SHALL report whether manual intervention is required

### Requirement: Installed xctestrun discovery is prebuilt-first

Runtime discovery MUST look under the resolved install directory's `ios-automation-server` child, select the alphabetically first regular `.xctestrun`, and construct `xcodebuild test-without-building -xctestrun <path>` with a destination using the detected simulator name. If no installed xctestrun exists but a source project does, startup MUST construct `xcodebuild test -project <path> -scheme IOSAutomationServer` instead.

#### Scenario: Prebuilt bundle exists

- **Given** an installed xctestrun and an available simulator
- **When** `ios_start_automation_server` starts its first process
- **Then** it SHALL use `test-without-building`, the discovered xctestrun, the detected simulator destination, and the automation-server UI test identifier

#### Scenario: Only source exists

- **Given** no installed xctestrun exists and a valid Xcode project is discoverable
- **When** startup runs
- **Then** it SHALL use the source-build `xcodebuild test` command

#### Scenario: Neither launch path exists

- **Given** neither a prebuilt xctestrun nor source project is discoverable
- **When** startup runs
- **Then** it MUST return guidance to rerun the installer on macOS or configure a cloned source project

### Requirement: Compatibility fallback is based on early process exit

Startup MUST poll a prebuilt launch for health every two seconds for up to 30 attempts. If that `xcodebuild` process exits before health succeeds and a source project is available, startup MUST retry once as a source-build fallback for up to 60 attempts. If no source project exists, it MUST return the early exit code and a shell-quoted command for diagnosis. VisionTest does not proactively compare Xcode versions.

#### Scenario: Prebuilt exits early on an incompatible host

- **Given** the prebuilt process exits nonzero before the server becomes healthy and a source project exists
- **When** startup observes the exit
- **Then** it SHALL launch the source-build fallback and return the fallback result

#### Scenario: Prebuilt exits early without source

- **Given** the prebuilt process exits before health succeeds and no source project exists
- **When** startup observes the exit
- **Then** it SHALL return diagnostic text containing the exit code and reproducible command and SHALL NOT claim the server started

#### Scenario: Prebuilt remains alive but never becomes healthy

- **Given** the prebuilt process remains alive through all 30 health attempts
- **When** the polling budget expires
- **Then** startup SHALL return timeout guidance and SHALL NOT start the source fallback, because fallback is triggered only by early process exit

## Verification

- Release production workflow: `.github/workflows/release.yaml`
- Installer production behavior: `install.sh`
- Runtime production discovery and launch: `app/src/main/kotlin/com/example/visiontest/discovery/ToolDiscovery.kt`, `app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt`, `app/src/main/kotlin/com/example/visiontest/config/IOSAutomationConfig.kt`
- Executable discovery and command-construction tests: `app/src/test/kotlin/com/example/visiontest/ToolFactoryPathTest.kt`
