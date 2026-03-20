## Why

When VisionTest is installed via `install.sh`, the iOS automation server (`ios_start_automation_server`) requires the user to clone the repo and build the Xcode project from source. This is a poor experience compared to Android, where APKs are now downloaded automatically. Maestro solves this by pre-building the XCUITest bundle in CI and shipping it — we should do the same.

## What Changes

- **Release workflow** adds a macOS job that runs `xcodebuild build-for-testing`, archives the test bundle (`.xctestrun` + derived data products), and publishes it as a release asset
- **install.sh** downloads and extracts the pre-built iOS test bundle on macOS (skipped on Linux)
- **MCP server `ios_start_automation_server`** gains a new code path: if a pre-built bundle is found, use `xcodebuild test-without-building -xctestrun` instead of `xcodebuild test -project` (skips compilation entirely)
- **iOS test bundle discovery** added to `ToolFactory` — checks install directory for `.xctestrun` file, with source-build fallback
- **`VISION_TEST_IOS_PROJECT_PATH` env var** kept as fallback for users who want to build from source or use a custom Xcode project
- **Documentation** updated to reflect the new zero-config iOS experience

## Capabilities

### New Capabilities
- `ios-bundle-release`: Building, archiving, and publishing the pre-built iOS XCUITest bundle as a release asset
- `ios-bundle-discovery`: MCP server discovery of pre-built test bundles and the `test-without-building` launch path
- `ios-bundle-install`: Downloading and extracting the iOS test bundle during installation (macOS only)

### Modified Capabilities

## Impact

- `.github/workflows/release.yaml` — new macOS job for `build-for-testing`, archive step, new release asset
- `install.sh` — conditional iOS bundle download on macOS
- `app/src/main/kotlin/com/example/visiontest/ToolFactory.kt` — `ios_start_automation_server` dual path (pre-built vs source), new `findXctestrun()` discovery
- `app/src/main/kotlin/com/example/visiontest/config/IOSAutomationConfig.kt` — new constants for xctestrun filenames
- `app/src/main/kotlin/com/example/visiontest/ToolFactory.kt` — extracted shared `resolveInstallDir()` helper (refactored from `findAutomationServerApk`)
- `CLAUDE.md` — updated iOS workflow docs, install description, env vars
