## 1. Release Workflow — iOS Build Job

- [x] 1.1 Add `ios-release-build` job in `.github/workflows/release.yaml` on `macos-26` that checks out code, selects Xcode, caches SPM deps, and runs `xcodebuild build-for-testing -project ... -scheme IOSAutomationServer -destination 'platform=iOS Simulator,name=iPhone 16' -derivedDataPath build/`
- [x] 1.2 Add archive step that creates `ios-automation-server.tar.gz` from `build/Build/Products/` (xctestrun + Debug-iphonesimulator/*.app)
- [x] 1.3 Generate `ios-automation-server.tar.gz.sha256` checksum
- [x] 1.4 Upload archive + checksum as GitHub Actions workflow artifacts using `actions/upload-artifact`
- [x] 1.5 Verify the `.xctestrun` file uses `__TESTROOT__` placeholders (not absolute CI paths) — add a CI step that greps the plist to confirm portability

## 2. Release Workflow — Include iOS Asset in Release

- [x] 2.1 Add `ios-release-build` to the `needs` list of the `release` job
- [x] 2.2 Add step in `release` job to download the iOS workflow artifact using `actions/download-artifact`
- [x] 2.3 Add `ios-automation-server.tar.gz` and `ios-automation-server.tar.gz.sha256` to the `softprops/action-gh-release` files list

## 3. Install Script — iOS Bundle Download

- [x] 3.1 Add `download_ios_bundle()` function in `install.sh` that downloads `ios-automation-server.tar.gz` + checksum using `download_and_verify`, extracts to `$RESOLVED_VISIONTEST_HOME/ios-automation-server/`
- [x] 3.2 Call `download_ios_bundle` in `main()` only on macOS arm64 (`uname -m` == `arm64`). Skip with info message on Linux and on macOS x86_64 (explain pre-built bundle is arm64-only, suggest building from source)
- [x] 3.3 Update success message: list iOS bundle on macOS arm64, note source-build-only on macOS x86_64, note macOS-only on Linux

## 4. MCP Server — iOS Test Bundle Discovery

- [x] 4.1 Add `XCTESTRUN_BUNDLE_DIR` constant to `IOSAutomationConfig.kt` (e.g., `"ios-automation-server"`)
- [x] 4.2 Extract shared `resolveInstallDir()` helper from `findAutomationServerApk()` install-dir resolution logic (`VISIONTEST_DIR` env var > default `~/.local/share/visiontest`), reuse in both APK and xctestrun discovery
- [x] 4.3 Add `findXctestrun()` method in `ToolFactory.kt` — private zero-arg version reads env vars, `internal` overload accepts explicit install dir (testable overload pattern, consistent with `findAutomationServerApk`)
- [x] 4.4 `findXctestrun()` globs for `*.xctestrun` in `<installDir>/ios-automation-server/`, returns first match sorted alphabetically (handles SDK-versioned filenames like `IOSAutomationServer_iphonesimulator18.0-arm64.xctestrun`)

## 5. MCP Server — Dual-Path Launch

- [x] 5.1 Refactor `ios_start_automation_server` to try `findXctestrun()` first — if found, build command with `xcodebuild test-without-building -xctestrun <path> -destination <simulator>`
- [x] 5.2 Keep existing `findXcodeProject()` path as fallback when no pre-built bundle is found
- [x] 5.3 Add automatic fallback: if `test-without-building` fails and source project is available, retry with source build path and log a warning about the pre-built bundle failure
- [x] 5.4 Update error message when neither pre-built bundle nor source project is found — mention re-running `install.sh` on macOS or cloning the repo
- [x] 5.5 Extract the xcodebuild command building into a helper to reduce duplication between the two paths
- [x] 5.6 Update `ios_start_automation_server` tool description to reflect dual-path behavior ("Uses pre-built test bundle if available, otherwise builds from source")

## 6. Unit Tests

- [x] 6.1 Add test: `findXctestrun finds xctestrun file in install directory` (using internal overload with temp dir)
- [x] 6.2 Add test: `findXctestrun returns null when install directory has no xctestrun`
- [x] 6.3 Add test: `findXctestrun returns null when install directory does not exist`
- [x] 6.4 Add test: `findXctestrun selects first file alphabetically when multiple xctestrun files exist`
- [~] 6.5 Add test: `findXctestrun uses VISIONTEST_DIR env var when set` — skipped: env var is read in private zero-arg overload; the internal overload takes explicit installDir, which is tested in 6.1
- [x] 6.6 Add test: `findXctestrun returns absolute path`
- [x] 6.7 Add test: xcodebuild command helper produces correct `test-without-building -xctestrun` command for pre-built path
- [x] 6.8 Add test: xcodebuild command helper produces correct `test -project` command for source path
- [x] 6.9 Run `./gradlew :app:test` to verify no regressions

## 7. Documentation

- [x] 7.1 Update CLAUDE.md iOS automation section to describe the pre-built bundle flow (zero-config for installed users)
- [x] 7.2 Update CLAUDE.md release assets list to include `ios-automation-server.tar.gz` + checksum
- [x] 7.3 Update CLAUDE.md install.sh description to mention iOS bundle download on macOS
- [x] 7.4 Document required Xcode version in CLAUDE.md prerequisites
