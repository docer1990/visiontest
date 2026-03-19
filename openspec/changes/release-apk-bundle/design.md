## Context

VisionTest's installer (`install.sh`) only downloads `visiontest.jar`. The Android automation APKs are built from the `automation-server/` Gradle module and never published as release assets. The iOS Xcode project path is discovered relative to the working directory or project root, which doesn't work for installed-from-release users.

The release workflow (`release.yaml`) runs on `ubuntu-latest` and currently only builds the fat JAR. The `automation-server-tests` job already sets up Android SDK, so the pattern exists.

## Goals / Non-Goals

**Goals:**
- Release workflow builds and publishes both Android APKs (main + test) with checksums
- `install.sh` downloads and verifies APKs alongside the JAR
- MCP server discovers APKs from the install directory as a fallback
- iOS project path is configurable via environment variable
- All new discovery paths are unit-tested

**Non-Goals:**
- Bundling the iOS Xcode project in the release (requires macOS + Xcode to build, must be cloned from source)
- Changing the existing APK discovery priority for dev workflows (env var and Gradle paths remain highest priority)
- Auto-updating installed APKs

## Decisions

### 1. APKs as flat release assets (not bundled inside the JAR)

Release assets alongside the JAR, downloaded separately by `install.sh`. Alternatives considered:
- **Embed APKs inside the JAR**: Complicates JAR build, extraction logic, and increases JAR size for users who don't need Android
- **Separate installer for Android**: More complexity, worse UX

Flat assets keep the install script simple and match the existing pattern.

### 2. Reusable `download_and_verify()` helper in install.sh

The current `download_jar()` function has download + checksum verification inline. Extracting a helper avoids duplicating the pattern for APKs and prevents trap conflicts with temp file cleanup. The helper takes `(url, checksum_url, dest_path)` and handles temp files, SHA-256 verification, and cleanup.

### 3. Install directory as lowest-priority fallback in APK discovery

Priority order: `VISION_TEST_APK_PATH` env var > Gradle build paths > install directory. This preserves dev workflows where the Gradle build output is preferred, while making installed APKs discoverable without configuration.

### 4. Simple APK filenames in install directory

Use `automation-server.apk` and `automation-server-test.apk` instead of the full Gradle output names. The `install_automation_server` tool's main APK derivation (string-replace on path) doesn't work for these names, so add a sibling-file fallback: if string-replace fails, check for `automation-server.apk` in the same directory.

### 5. Environment variable for iOS project path

`VISION_TEST_IOS_PROJECT_PATH` checked first in `findXcodeProject()`. This is the simplest approach — no need to bundle or download the project. Users who install from release and want iOS support clone the repo and set the env var.

## Risks / Trade-offs

- **[Larger release assets]** Two APKs add ~5-10MB to the release. Mitigation: APKs are only downloaded during install, not at runtime. Acceptable trade-off for functionality.
- **[Android SDK required in release workflow]** Adds build time and a dependency on `android-actions/setup-android`. Mitigation: Already used in the test job; pin to same SHA.
- **[APK version drift]** Installed APKs could become stale if user updates the JAR but not the APKs. Mitigation: `install.sh` always downloads all assets together. Version mismatch is a future concern if we add partial updates.
