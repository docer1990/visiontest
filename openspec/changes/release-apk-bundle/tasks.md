## 1. Release Workflow APK Build

- [x] 1.1 Add Android SDK setup step to the `release` job in `.github/workflows/release.yaml` (same SHA-pinned `android-actions/setup-android` action as `automation-server-tests` job)
- [x] 1.2 Add `Accept Android licenses` and `Build Android APKs` steps (`assembleDebug` + `assembleDebugAndroidTest`)
- [x] 1.3 Add staging step to copy APKs to simple names (`automation-server.apk`, `automation-server-test.apk`) and generate `.sha256` checksum files
- [x] 1.4 Add all 4 new APK assets to the `softprops/action-gh-release` files list

## 2. Install Script APK Downloads

- [x] 2.1 Extract `download_and_verify(url, checksum_url, dest_path)` helper from existing `download_jar` logic in `install.sh`
- [x] 2.2 Refactor `download_jar` to use the new helper
- [x] 2.3 Add `download_apks` function that downloads both APKs using the helper with same security (umask, chmod, SHA-256 verification)
- [x] 2.4 Call `download_apks` after `download_jar` in `main()` and update success message

## 3. APK Install Directory Discovery

- [x] 3.1 Add `installDir: File?` parameter to the testable `findAutomationServerApk(envApkPath, searchRoots)` overload in `ToolFactory.kt`
- [x] 3.2 Implement install-dir fallback: after env var and search roots, check for `automation-server-test.apk` in `installDir`
- [x] 3.3 Update no-arg `findAutomationServerApk()` to resolve install dir from `VISIONTEST_DIR` env var or `~/.local/share/visiontest` and pass it to the overload

## 4. Main APK Derivation Fix

- [x] 4.1 In the `install_automation_server` tool, after the existing string-replace derivation for main APK path, add fallback: if derived file doesn't exist, check for `automation-server.apk` in the same directory as the test APK
- [x] 4.2 Update error message when APK not found to mention re-running `install.sh` or setting `VISION_TEST_APK_PATH`

## 5. iOS Project Path Environment Variable

- [x] 5.1 Add `XCODE_PROJECT_PATH_ENV = "VISION_TEST_IOS_PROJECT_PATH"` constant to `IOSAutomationConfig.kt`
- [x] 5.2 Update `findXcodeProject()` in `ToolFactory.kt` to check the env var first before CWD/project root/code source
- [x] 5.3 Update error message in `ios_start_automation_server` to include `git clone` and `export VISION_TEST_IOS_PROJECT_PATH=...` instructions

## 6. Unit Tests

- [x] 6.1 Add test: `findAutomationServerApk finds APK in installDir`
- [x] 6.2 Add test: `findAutomationServerApk prefers env var over installDir`
- [x] 6.3 Add test: `findAutomationServerApk prefers search roots over installDir`
- [x] 6.4 Add test: `findAutomationServerApk returns null when installDir has no APK`
- [x] 6.5 Add test: `findAutomationServerApk returns null when installDir is null`
- [x] 6.6 Run `./gradlew test` to verify no regressions

## 7. Documentation

- [x] 7.1 Add `VISION_TEST_IOS_PROJECT_PATH` to the Environment Variables table in `CLAUDE.md`
- [x] 7.2 Update `install.sh` description in `CLAUDE.md` to mention APK downloads
- [x] 7.3 Update release assets list in `CLAUDE.md` to include APKs + checksums
- [x] 7.4 Add comment to `run-visiontest.sh` noting installed APKs are discovered automatically
