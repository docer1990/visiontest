# Installation and Distribution

## Prerequisites

- JDK 17 or newer
- macOS or Linux on `arm64`/`aarch64` or `x86_64`/`amd64`
- Android Platform Tools (`adb`) for Android device automation
- The full Xcode IDE plus a compatible iOS Simulator runtime for all iOS automation on macOS; Xcode Command Line Tools alone are insufficient, including when using the prebuilt bundle
- The Android SDK only when building the Android automation server from source

The prebuilt iOS bundle is available only on macOS arm64 and must be compatible with the installed Xcode major version. Linux and macOS x86_64 installations still receive the JAR and Android APKs; the installer skips the iOS bundle and directs Intel Mac users to a source build.

## One-command installer

```bash
curl -fsSL https://github.com/docer1990/visiontest/releases/latest/download/install.sh | bash
```

`install.sh` performs these steps:

1. Detects macOS or Linux and normalizes the supported CPU architecture.
2. Requires Java 17 or newer.
3. Reads and validates the latest GitHub release tag.
4. Downloads and verifies `visiontest.jar` using `visiontest.jar.sha256`.
5. Downloads and verifies `automation-server.apk` and `automation-server-test.apk` using their matching `.sha256` files.
6. On macOS arm64, downloads and verifies `ios-automation-server.tar.gz`, validates its entries, extracts it to a staging directory, and replaces the previous bundle using a backup-and-restore sequence.
7. Creates the executable wrapper at `~/.local/bin/visiontest`. If that directory is missing from `PATH`, the installer appends a PATH entry to each existing `~/.bashrc` and `~/.zshrc`; on macOS it also creates `~/.zshrc` when that file does not exist.

The default data directory is `~/.local/share/visiontest/`. Set `VISIONTEST_DIR` to choose another directory under `$HOME`; blank values use the default, and symlinked or out-of-home destinations are rejected.

After a normal network installation, the relevant paths are:

| Installed item | Path |
|---|---|
| MCP/CLI JAR | `$VISIONTEST_DIR/visiontest.jar` |
| JAR checksum | `$VISIONTEST_DIR/visiontest.jar.sha256` |
| Android app APK | `$VISIONTEST_DIR/automation-server.apk` |
| Android app checksum | `$VISIONTEST_DIR/automation-server.apk.sha256` |
| Android instrumentation APK | `$VISIONTEST_DIR/automation-server-test.apk` |
| Android instrumentation checksum | `$VISIONTEST_DIR/automation-server-test.apk.sha256` |
| Installed release version | `$VISIONTEST_DIR/version.txt` |
| Extracted iOS bundle, macOS arm64 only | `$VISIONTEST_DIR/ios-automation-server/` |
| CLI wrapper | `~/.local/bin/visiontest` |

Here `$VISIONTEST_DIR` means the resolved configured directory or its default, `~/.local/share/visiontest`. The downloaded iOS archive and checksum are removed after successful extraction.

For local installer testing, `bash install.sh --local-jar app/build/libs/visiontest.jar` installs only the supplied JAR and records `local-dev`; it intentionally skips the APKs and iOS bundle.

## Native automation-server compatibility

Some UI operations add a JSON-RPC method to the native automation server. A JAR
upgrade alone does not add that method to an Android device or an extracted iOS
bundle. For `tap_on_element`, update the Android automation APK pair with
`visiontest install_automation_server -p android`, and rerun the installer (or
use a current source build) to update the iOS automation bundle. An older native
server responds to `ui.tapOnElement` with a method-not-found operation error.

The installer uses a restrictive umask, verifies every downloaded binary with SHA-256, limits the install directory to `$HOME`, and validates archive entries before replacing an existing iOS bundle. Bundle replacement is staged: the current directory is moved to a process-specific backup, the staged directory is moved into place, and the backup is restored if that second move fails. This is not transactional across a process or machine crash: an interruption after the old directory is backed up but before the new directory is installed or the restore runs can leave the final bundle path absent, while an interruption after installation but before cleanup can leave a stale backup.

## Release assets

The release workflow publishes:

- `visiontest.jar` and `visiontest.jar.sha256`
- `automation-server.apk` and `automation-server.apk.sha256`
- `automation-server-test.apk` and `automation-server-test.apk.sha256`
- `ios-automation-server.tar.gz` and `ios-automation-server.tar.gz.sha256`
- `install.sh`
- `run-visiontest.sh`

See the [release process](release.md) for publishing and verification instructions.

## Launcher script

`run-visiontest.sh` is intended for development and desktop MCP configuration. It resolves the JAR in this order:

1. `app/build/libs/visiontest.jar` in a source checkout, with Android SDK and APK path setup.
2. `~/.local/share/visiontest/visiontest.jar` from the default installation.
3. An error with build and installation instructions.

The installed `visiontest` wrapper honors the configured install directory recorded when `install.sh` creates it, with the default JAR as a fallback.

## CLI usage

Running `visiontest` without arguments starts the MCP stdio server. Root help and version are available without selecting a device platform:

```bash
visiontest --help
visiontest --version
```

Only device-operation subcommands require `--platform android` or `--platform ios` (short form `-p`):

```bash
visiontest screenshot --platform android
visiontest get_interactive_elements -p ios
visiontest tap_by_coordinates --platform android 540 1200
```

Project setup is separate and does not accept `--platform`:

```bash
visiontest init --agent claude,opencode,codex
```

Run `visiontest --help` for the complete command list and see the
[embedded agent instructions](../app/src/main/resources/agent-instructions.md)
for the standard automation loop.
