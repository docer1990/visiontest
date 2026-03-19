## Context

Currently, `ios_start_automation_server` runs `xcodebuild test -project ... -scheme ...` which compiles the Xcode project from source every time. This requires the user to have the repo cloned and Xcode installed. The `release-apk-bundle` change solved the equivalent Android problem by bundling pre-built APKs. We now need the same for iOS.

The key insight from Maestro: `xcodebuild build-for-testing` produces a `.xctestrun` file and compiled test products that can be archived and later run with `xcodebuild test-without-building -xctestrun <path>`. This separates the build step (CI) from the run step (user's machine).

The release workflow already has an `ios-automation-tests` job on `macos-26` that runs `xcodebuild build-for-testing`. We can extend this or add a dedicated build step in the release job.

## Goals / Non-Goals

**Goals:**
- Zero-config iOS automation for installed users on macOS (no cloning, no source build)
- Pre-built test bundle shipped as a release asset, downloaded by `install.sh` on macOS
- `ios_start_automation_server` uses `test-without-building` when a pre-built bundle is available
- Source-build path preserved as fallback (Xcode project found via existing discovery)

**Non-Goals:**
- Supporting real iOS devices (only simulators, same as today)
- Supporting multiple Xcode versions in the same release (ship one, document the requirement)
- Linux support for iOS automation (macOS-only by nature)

## Decisions

### 1. Archive format: tar.gz of derived data products + xctestrun

`xcodebuild build-for-testing -derivedDataPath build/` produces:
- `build/Build/Products/*.xctestrun` — the test plan file
- `build/Build/Products/Debug-iphonesimulator/IOSAutomationServer.app` — host app
- `build/Build/Products/Debug-iphonesimulator/IOSAutomationServerUITests-Runner.app` — test runner

We tar these into `ios-automation-server.tar.gz` for the release. At install time, extract to `~/.local/share/visiontest/ios-automation-server/`.

Alternatives considered:
- **Zip**: tar.gz is more standard on macOS/Linux, and we already use shell-based install
- **Ship .app bundles separately**: More files to manage, and the `.xctestrun` references them by relative path — keeping the directory structure intact is simpler

### 2. xctestrun destination rewriting at runtime

The `.xctestrun` file baked during CI contains a hardcoded `__TESTHOST__` path and simulator destination. At runtime, we need to:
- Pass `-destination 'platform=iOS Simulator,name=<actual simulator>'` to `xcodebuild test-without-building`
- The `__TESTHOST__` and `__PLATFORMS__` placeholders in `.xctestrun` are resolved by xcodebuild relative to the products directory

No manual rewriting is needed — `xcodebuild test-without-building -xctestrun <path> -destination <dest>` handles this natively. The `-destination` flag overrides whatever was baked in.

### 3. macOS-only download in install.sh

On Linux, skip the iOS bundle download entirely with an info message. iOS automation only works on macOS. This avoids downloading ~50MB of unnecessary data on Linux.

### 4. Release workflow: separate macOS job for iOS build

The release job currently runs on `ubuntu-latest` (for JAR + APKs). iOS builds require macOS + Xcode. Add a new `ios-release-build` job on `macos-26` that:
1. Runs `xcodebuild build-for-testing -derivedDataPath build/`
2. Archives the products into `ios-automation-server.tar.gz`
3. Uploads as a workflow artifact

The existing `release` job then downloads this artifact and includes it in the GitHub Release. This mirrors how the test job already works but adds the archive step.

### 5. Dual-path launch in ios_start_automation_server

Discovery order:
1. **Pre-built bundle**: Check for `.xctestrun` in install dir → use `test-without-building`
2. **Source project**: Use existing `findXcodeProject()` → use `xcodebuild test` (current behavior)

This means dev workflows (running from repo) continue to work as before, while installed users get the fast path automatically.

## Risks / Trade-offs

- **[Xcode version coupling]** The pre-built bundle is compiled against a specific Xcode/SDK version. If the user has a different Xcode version, `test-without-building` may fail. → Mitigation: Document the required Xcode version. Fall back to source build if pre-built fails. Log a clear error message suggesting source build.
- **[Larger release asset]** ~50MB for the iOS bundle. → Mitigation: Only downloaded on macOS. tar.gz compression helps. Acceptable trade-off for zero-config experience.
- **[CI cost]** macOS runners are more expensive. → Mitigation: The `ios-automation-tests` job already runs on macOS — we're adding build artifact packaging, not a new build from scratch.
- **[Simulator name mismatch]** The `.xctestrun` may reference a simulator that doesn't exist on the user's machine. → Mitigation: `-destination` flag overrides this. We already detect the booted simulator name.
