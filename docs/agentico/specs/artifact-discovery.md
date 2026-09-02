# Artifact discovery

## Purpose

Artifact discovery locates Android installation APKs, an iOS source project, and an installed iOS test bundle from explicit configuration, source checkouts, and binary-install locations with deterministic precedence.

## Requirements

### Requirement: The install directory has explicit resolution precedence

The shared install directory resolver MUST use the first nonblank value among `VISIONTEST_DIR`, the directory containing the running VisionTest JAR, and `${user.home}/.local/share/visiontest`, in that order.

#### Scenario: Custom install directory

- **Given** `VISIONTEST_DIR` contains a nonblank path
- **When** artifact discovery resolves the install directory
- **Then** it SHALL use that path for installed APK and xctestrun lookup

#### Scenario: Co-located running JAR

- **Given** `VISIONTEST_DIR` is unset or blank and VisionTest is running from a JAR file
- **When** the install directory is resolved
- **Then** it SHALL use the JAR's parent directory before considering the user-home default

### Requirement: Android test APK discovery follows a fixed precedence

Android discovery MUST check an existing path from `VISION_TEST_APK_PATH` first, then the Gradle androidTest output path beneath each source search root in supplied order, and finally `automation-server-test.apk` in the resolved install directory. The production source roots SHALL be current working directory, code-source root when available, and the Gradle project root discovered from the current directory.

#### Scenario: Explicit APK exists

- **Given** `VISION_TEST_APK_PATH` names an existing path and other candidates exist
- **When** the test APK is discovered
- **Then** the explicit path SHALL win

#### Scenario: Explicit APK is missing

- **Given** `VISION_TEST_APK_PATH` names a missing path and a later source or install candidate exists
- **When** discovery runs
- **Then** it SHALL warn about the missing override and continue in normal precedence order

#### Scenario: Installed fallback

- **Given** no explicit or source-tree test APK exists and the install directory contains `automation-server-test.apk`
- **When** discovery runs
- **Then** it SHALL return that installed path

### Requirement: Android main and test APKs are resolved as a pair

Given a discovered Gradle androidTest APK, main-APK resolution MUST derive the debug main path by removing the first `androidTest/` segment and `-androidTest` suffix fragment and MUST return it only if it exists and differs from the input. Given an installed `automation-server-test.apk`, resolution MUST instead require sibling `automation-server.apk`. It MUST return null when no corresponding main APK exists.

#### Scenario: Gradle output pair

- **Given** both Gradle debug main and androidTest APKs exist in their standard output directories
- **When** the test APK is selected
- **Then** main-APK resolution SHALL return the debug main APK

#### Scenario: Installed pair

- **Given** the install directory contains `automation-server-test.apk` and sibling `automation-server.apk`
- **When** main-APK resolution runs
- **Then** it SHALL return the sibling main APK rather than treating the test APK as both artifacts

### Requirement: Xcode source-project discovery validates candidates

An Xcode project candidate MUST exist, be a directory, and have a name ending in `.xcodeproj`. Discovery MUST prefer a valid `VISION_TEST_IOS_PROJECT_PATH`, then the configured relative project path from the current working directory, the discovered Gradle project root, and the code-source root. An invalid explicit candidate MUST be logged and MUST NOT prevent fallback.

#### Scenario: Valid explicit Xcode project

- **Given** `VISION_TEST_IOS_PROJECT_PATH` names an existing `.xcodeproj` directory
- **When** source-project discovery runs
- **Then** it SHALL return that absolute path without using a later candidate

#### Scenario: Invalid explicit Xcode project

- **Given** the environment path is missing or is not an existing `.xcodeproj` directory
- **When** a valid source-tree candidate exists
- **Then** discovery SHALL continue and return the source-tree candidate

### Requirement: Project-root search is bounded

Project-root discovery MUST walk upward looking for `settings.gradle.kts` or `settings.gradle` and MUST inspect no more than ten directory levels from the normalized starting point.

#### Scenario: Marker is beyond the bound

- **Given** no Gradle settings marker exists in the first ten inspected directories
- **When** project-root discovery runs
- **Then** it SHALL return null rather than continuing to the filesystem root indefinitely

### Requirement: Installed xctestrun discovery is deterministic

iOS prebuilt discovery MUST search only the `ios-automation-server` child of the resolved install directory for regular files ending in `.xctestrun`. It MUST return an absolute path to the alphabetically first match or null when none exists.

#### Scenario: Multiple xctestrun files

- **Given** the installed bundle directory contains multiple xctestrun files
- **When** discovery runs
- **Then** it SHALL select the alphabetically first filename

#### Scenario: Only app bundles or unrelated files exist

- **Given** the installed bundle directory contains no regular `.xctestrun` file
- **When** discovery runs
- **Then** it SHALL return null

### Requirement: iOS startup prefers installed artifacts without suppressing source discovery

The iOS startup handler MUST discover both the installed xctestrun and source project, MUST choose the xctestrun when present, and MUST use the source project when no xctestrun is available. If neither exists, it MUST return guidance covering installation and source configuration.

#### Scenario: Both iOS paths exist

- **Given** both an installed xctestrun and a valid Xcode project are discoverable
- **When** iOS startup chooses its initial command
- **Then** it SHALL choose `xcodebuild test-without-building -xctestrun` while retaining the project as a possible early-exit fallback

## Verification

- Production discovery and startup: `app/src/main/kotlin/com/example/visiontest/discovery/ToolDiscovery.kt`, `app/src/main/kotlin/com/example/visiontest/config/IOSAutomationConfig.kt`, `app/src/main/kotlin/com/example/visiontest/tools/AndroidAutomationToolRegistrar.kt`, `app/src/main/kotlin/com/example/visiontest/tools/IOSAutomationToolRegistrar.kt`
- Executable tests: `app/src/test/kotlin/com/example/visiontest/ToolFactoryPathTest.kt`
