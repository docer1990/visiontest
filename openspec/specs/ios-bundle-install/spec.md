## ADDED Requirements

### Requirement: Install script downloads iOS bundle on macOS
The `install.sh` script SHALL download `ios-automation-server.tar.gz` and its checksum on macOS, verify integrity, and extract to the install directory.

#### Scenario: macOS Apple Silicon installation
- **WHEN** `install.sh` runs on macOS with `arm64` architecture (`uname -m` returns `arm64`)
- **THEN** the iOS test bundle is downloaded, SHA-256 verified, and extracted to `~/.local/share/visiontest/ios-automation-server/`

#### Scenario: macOS Intel installation skips iOS bundle
- **WHEN** `install.sh` runs on macOS with `x86_64` architecture
- **THEN** the iOS bundle download is skipped with an informational message explaining the pre-built bundle is arm64-only and iOS automation requires building from source (clone repo + Xcode)

#### Scenario: Linux installation skips iOS bundle
- **WHEN** `install.sh` runs on Linux
- **THEN** the iOS bundle download is skipped with an informational message

#### Scenario: iOS bundle checksum mismatch
- **WHEN** the SHA-256 checksum of the downloaded archive does not match
- **THEN** the installation fails with a clear error message

### Requirement: Extracted bundle preserves directory structure
The extracted bundle SHALL maintain the relative paths expected by `xcodebuild test-without-building -xctestrun`.

#### Scenario: Correct extraction structure
- **WHEN** the tar.gz is extracted
- **THEN** the `.xctestrun` file and `Debug-iphonesimulator/` directory with `.app` bundles are present under the extraction path

### Requirement: Install success message includes iOS status
The install success message SHALL indicate whether the iOS automation bundle was installed (macOS) or skipped (Linux).

#### Scenario: macOS arm64 success message
- **WHEN** installation completes on macOS arm64
- **THEN** the success message lists the iOS bundle alongside the JAR and APKs

#### Scenario: macOS x86_64 success message
- **WHEN** installation completes on macOS x86_64
- **THEN** the success message notes iOS automation requires building from source

#### Scenario: Linux success message
- **WHEN** installation completes on Linux
- **THEN** the success message does not list iOS bundle but mentions it's macOS-only
