package com.example.visiontest.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice

/**
 * Reusable `--platform` / `-p` option for cross-platform CLI subcommands.
 * Returns `"android"` or `"ios"`.
 */
fun CliktCommand.platformOption() =
    option("--platform", "-p", help = "Target platform: android or ios")
        .choice("android", "ios")
        .required()

/**
 * `--platform` option for Android-only CLI subcommands.
 * Accepts only `"android"`. Passing `"ios"` triggers a [CliExit] with [ExitCode.PlatformNotSupported].
 */
fun CliktCommand.androidOnlyPlatformOption() =
    option("--platform", "-p", help = "Target platform (android only)")
        .choice("android", "ios")
        .required()

/**
 * Validates that a platform value is `"android"` for Android-only commands.
 * Throws [CliExit] with [ExitCode.PlatformNotSupported] if `"ios"` is provided.
 */
fun requireAndroid(platform: String) {
    if (platform == "ios") {
        throw CliExit(ExitCode.PlatformNotSupported, "This command is Android-only.")
    }
}
