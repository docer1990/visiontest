## ADDED Requirements

### Requirement: Release workflow builds iOS test bundle
The release workflow SHALL include a macOS job that runs `xcodebuild build-for-testing` with a fixed `derivedDataPath` and produces the compiled test products.

#### Scenario: Successful iOS build on tag push
- **WHEN** a git tag matching `v*` is pushed
- **THEN** the macOS job runs `xcodebuild build-for-testing -project ios-automation-server/IOSAutomationServer.xcodeproj -scheme IOSAutomationServer -destination 'platform=iOS Simulator,name=iPhone 16' -derivedDataPath build/` and produces the `.xctestrun` file, host app, and test runner app

### Requirement: iOS test bundle archived as tar.gz
The release workflow SHALL archive the build products directory into `ios-automation-server.tar.gz` preserving the directory structure needed by `test-without-building`.

#### Scenario: Archive contains required files
- **WHEN** the archive step completes
- **THEN** `ios-automation-server.tar.gz` contains the `.xctestrun` file and the `Debug-iphonesimulator/` directory with both `.app` bundles

### Requirement: iOS bundle published as release asset with checksum
The release SHALL include `ios-automation-server.tar.gz` and `ios-automation-server.tar.gz.sha256` as release assets.

#### Scenario: Assets available in GitHub Release
- **WHEN** the release is created
- **THEN** `ios-automation-server.tar.gz` and its SHA-256 checksum are listed as downloadable release assets

### Requirement: iOS build artifact passed to release job
Since the iOS build runs on macOS and the release job runs on Ubuntu, the archive SHALL be passed between jobs using GitHub Actions workflow artifacts.

#### Scenario: Cross-job artifact transfer
- **WHEN** the macOS iOS build job completes
- **THEN** the release job downloads the archived test bundle and includes it in the GitHub Release

### Requirement: xctestrun uses relative path placeholders
The `.xctestrun` file produced by `build-for-testing` SHALL use `__TESTROOT__` placeholders for test product paths, ensuring portability when extracted to a different directory on the user's machine.

#### Scenario: xctestrun contains __TESTROOT__ references
- **WHEN** the build-for-testing step completes
- **THEN** the `.xctestrun` file references test products via `__TESTROOT__` relative to the products directory, not absolute paths from the CI build machine
