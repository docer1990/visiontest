## 1. Release Workflow — iOS Build Job

- [ ] 1.1 Add `ios-release-build` job in `.github/workflows/release.yaml` on `macos-26` that checks out code, selects Xcode, caches SPM deps, and runs `xcodebuild build-for-testing -derivedDataPath build/`
- [ ] 1.2 Add archive step that creates `ios-automation-server.tar.gz` from `build/Build/Products/` (xctestrun + Debug-iphonesimulator/*.app)
- [ ] 1.3 Generate `ios-automation-server.tar.gz.sha256` checksum
- [ ] 1.4 Upload archive + checksum as GitHub Actions workflow artifacts using `actions/upload-artifact`

## 2. Release Workflow — Include iOS Asset in Release

- [ ] 2.1 Add `ios-release-build` to the `needs` list of the `release` job
- [ ] 2.2 Add step in `release` job to download the iOS workflow artifact using `actions/download-artifact`
- [ ] 2.3 Add `ios-automation-server.tar.gz` and `ios-automation-server.tar.gz.sha256` to the `softprops/action-gh-release` files list

## 3. Install Script — iOS Bundle Download

- [ ] 3.1 Add `download_ios_bundle()` function in `install.sh` that downloads `ios-automation-server.tar.gz` + checksum using `download_and_verify`, extracts to `$RESOLVED_VISIONTEST_HOME/ios-automation-server/`
- [ ] 3.2 Call `download_ios_bundle` in `main()` only on macOS (skip with info message on Linux)
- [ ] 3.3 Update success message to list iOS bundle on macOS, or note macOS-only on Linux

## 4. MCP Server — iOS Test Bundle Discovery

- [ ] 4.1 Add `XCTESTRUN_BUNDLE_DIR` constant to `IOSAutomationConfig.kt` (e.g., `"ios-automation-server"`)
- [ ] 4.2 Add `findXctestrun()` method in `ToolFactory.kt` that searches for `.xctestrun` files in install dir's `ios-automation-server/` subdirectory
- [ ] 4.3 Resolve install dir in `findXctestrun()` from `VISIONTEST_DIR` env var or default `~/.local/share/visiontest`

## 5. MCP Server — Dual-Path Launch

- [ ] 5.1 Refactor `ios_start_automation_server` to try `findXctestrun()` first — if found, build command with `xcodebuild test-without-building -xctestrun <path> -destination <simulator>`
- [ ] 5.2 Keep existing `findXcodeProject()` path as fallback when no pre-built bundle is found
- [ ] 5.3 Update error message when neither pre-built bundle nor source project is found — mention re-running `install.sh` on macOS or cloning the repo
- [ ] 5.4 Extract the xcodebuild command building into a helper to reduce duplication between the two paths

## 6. Unit Tests

- [ ] 6.1 Add test: `findXctestrun finds xctestrun file in install directory`
- [ ] 6.2 Add test: `findXctestrun returns null when install directory has no xctestrun`
- [ ] 6.3 Add test: `findXctestrun returns null when install directory does not exist`
- [ ] 6.4 Run `./gradlew :app:test` to verify no regressions

## 7. Documentation

- [ ] 7.1 Update CLAUDE.md iOS automation section to describe the pre-built bundle flow (zero-config for installed users)
- [ ] 7.2 Update CLAUDE.md release assets list to include `ios-automation-server.tar.gz` + checksum
- [ ] 7.3 Update CLAUDE.md install.sh description to mention iOS bundle download on macOS
- [ ] 7.4 Document required Xcode version in CLAUDE.md prerequisites
