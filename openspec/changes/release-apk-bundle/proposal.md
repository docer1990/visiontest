## Why

When VisionTest is installed via `install.sh`, only `visiontest.jar` is downloaded. All Android UI automation tools fail because the two APKs (main + test) required by `install_automation_server` are missing. Similarly, the iOS automation server can't find its Xcode project since it's not bundled. This blocks any installed-from-release user from using the core automation features.

## What Changes

- **Release workflow** builds Android APKs and publishes them as release assets alongside the JAR
- **install.sh** downloads and verifies both APKs during installation
- **ToolFactory APK discovery** gains an install-directory fallback so the MCP server finds APKs placed by the installer
- **Main APK derivation** in `install_automation_server` handles the simple installed filenames (not just Gradle build paths)
- **iOS project discovery** gains a `VISION_TEST_IOS_PROJECT_PATH` env var override for users who clone the repo elsewhere
- **Error messages** updated to guide users toward re-running `install.sh` or setting env vars
- **Unit tests** cover the new install-directory discovery path
- **Documentation** updated to reflect new env vars, assets, and install behavior

## Capabilities

### New Capabilities
- `apk-release-bundle`: Building, publishing, and downloading Android APKs as part of the release and install flow
- `apk-install-discovery`: MCP server discovery of APKs from the install directory (`~/.local/share/visiontest/`)
- `ios-project-env-override`: Environment variable override for iOS Xcode project path

### Modified Capabilities

## Impact

- `.github/workflows/release.yaml` — new Android SDK setup + APK build + release assets
- `install.sh` — new download helper, APK download function, updated success message
- `app/src/main/kotlin/com/example/visiontest/ToolFactory.kt` — `findAutomationServerApk()` gains `installDir` fallback, main APK derivation updated, `findXcodeProject()` checks env var first
- `app/src/main/kotlin/com/example/visiontest/config/IOSAutomationConfig.kt` — new env var constant
- `app/src/test/kotlin/com/example/visiontest/ToolFactoryPathTest.kt` — new install-dir tests
- `CLAUDE.md` — updated env vars table, release assets list, install description
- `run-visiontest.sh` — comment about automatic APK discovery
