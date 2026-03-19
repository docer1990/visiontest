## ADDED Requirements

### Requirement: Release workflow builds Android APKs
The release workflow SHALL build both Android APKs (debug main and debug androidTest) using the Android SDK setup already used in the test job. The workflow SHALL use SHA-pinned actions.

#### Scenario: Successful APK build on tag push
- **WHEN** a git tag matching `v*` is pushed
- **THEN** the release job sets up Android SDK, runs `./gradlew :automation-server:assembleDebug :automation-server:assembleDebugAndroidTest`, and produces both APKs

### Requirement: Release includes APK assets with checksums
The release SHALL include `automation-server.apk`, `automation-server-test.apk`, and their corresponding `.sha256` checksum files as release assets, alongside the existing JAR assets.

#### Scenario: APKs published as release assets
- **WHEN** the release job completes successfully
- **THEN** the GitHub Release contains `automation-server.apk`, `automation-server.apk.sha256`, `automation-server-test.apk`, and `automation-server-test.apk.sha256`

#### Scenario: APKs copied with simple names
- **WHEN** APKs are staged for release
- **THEN** the Gradle output APKs are copied to staging with simple names (`automation-server.apk` and `automation-server-test.apk`)

### Requirement: Install script downloads and verifies APKs
The `install.sh` script SHALL download both APKs and their checksums during installation, using the same security measures as the JAR download (umask 077, chmod 600, SHA-256 verification, temp files).

#### Scenario: APKs downloaded during install
- **WHEN** a user runs `install.sh`
- **THEN** both `automation-server.apk` and `automation-server-test.apk` are downloaded to the install directory with verified checksums

#### Scenario: APK checksum mismatch
- **WHEN** an APK's SHA-256 checksum does not match
- **THEN** the installation fails with a clear error message

### Requirement: Reusable download helper
The `install.sh` script SHALL use a reusable `download_and_verify` helper function for all asset downloads (JAR and APKs) to avoid code duplication and trap conflicts.

#### Scenario: Helper used for JAR download
- **WHEN** `download_jar` is called
- **THEN** it delegates to `download_and_verify` with the JAR URL, checksum URL, and destination path

#### Scenario: Helper used for APK downloads
- **WHEN** `download_apks` is called
- **THEN** it calls `download_and_verify` twice, once for each APK
