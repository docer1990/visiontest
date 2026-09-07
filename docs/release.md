# Release Process

## Prerequisites

Prepare a release from the repository root on the commit intended for publication.

1. Confirm the working tree is clean:

   ```bash
   git status --short
   ```

   A clean tree produces no output.

2. Choose `X.Y.Z` and update both version declarations to that value:

   - `version` in `app/build.gradle.kts`, which becomes the JAR `Implementation-Version` and CLI/MCP version
   - `versionName` in `automation-server/build.gradle.kts`

3. Confirm both files agree and commit the version change before tagging. The release tag is the same value with a `v` prefix: `vX.Y.Z`.
4. Ensure the GitHub CLI is authenticated before using it for remote checks:

   ```bash
   gh auth status
   ```

## Local verification

Run the complete local build before publishing:

```bash
./gradlew build
```

This exercises the Kotlin/JVM tests, packaged-JAR end-to-end tests, coverage gate, static analysis, and Android module build checks. The release workflow additionally runs the iOS tests and automation-server smoke check on its macOS runner.

## Publish

Create and push the release tag only after the clean-tree, version, and build checks pass:

```bash
git tag vX.Y.Z
git push origin vX.Y.Z
```

Tags matching `v*` trigger `.github/workflows/release.yaml`.

## Pipeline outputs

The release workflow publishes exactly these GitHub Release assets:

| Asset | Purpose |
|---|---|
| `visiontest.jar` | Kotlin MCP server and CLI fat JAR |
| `visiontest.jar.sha256` | SHA-256 checksum for the JAR |
| `automation-server.apk` | Android host application |
| `automation-server.apk.sha256` | SHA-256 checksum for the host APK |
| `automation-server-test.apk` | Android instrumentation server |
| `automation-server-test.apk.sha256` | SHA-256 checksum for the instrumentation APK |
| `ios-automation-server.tar.gz` | Portable macOS arm64 XCUITest bundle |
| `ios-automation-server.tar.gz.sha256` | SHA-256 checksum for the iOS bundle |
| `install.sh` | Network installer |
| `run-visiontest.sh` | Source-checkout and default-install launcher |

The iOS build job creates its archive and checksum on macOS. The final release job builds the JAR and both APKs, generates their three checksums, downloads the iOS artifact, and uploads all ten files.

## Verification

Inspect the workflow run and published release:

```bash
gh run list --workflow release.yaml
gh release view vX.Y.Z
```

Confirm the run for `vX.Y.Z` completed successfully and the release contains every asset listed above. To inspect an individual run in more detail, copy its database ID from `gh run list` and use:

```bash
gh run view RUN_ID
```

## Recovery

If the workflow fails, keep the tag and release state intact while diagnosing it:

```bash
gh run list --workflow release.yaml
gh run view RUN_ID --log-failed
```

Identify the failing job first: Kotlin/MCP tests, Android tests, iOS tests or smoke check, iOS bundle construction, or final release staging. Reproduce the corresponding command locally where the required platform is available. For a transient runner or network failure, retry only the failed jobs:

```bash
gh run rerun RUN_ID --failed
```

For a source or configuration defect, fix it on a new commit and make an explicit release decision with the maintainers; publishing a new patch version is the normal non-destructive path.

Do not delete or recreate a published tag or GitHub Release as a routine retry. Rewriting a published release is destructive for downstream users and requires an explicit rollback or replacement decision.
