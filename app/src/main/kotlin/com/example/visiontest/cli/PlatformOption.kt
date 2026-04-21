package com.example.visiontest.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice

/**
 * Target platform for CLI commands.
 */
enum class Platform(val value: String) {
    Android("android"),
    Ios("ios");
}

/**
 * Reusable `--platform` / `-p` option for cross-platform CLI subcommands.
 * Returns a [Platform] enum value.
 */
fun CliktCommand.platformOption() =
    option("--platform", "-p", help = "Target platform: android or ios")
        .choice("android" to Platform.Android, "ios" to Platform.Ios)
        .required()

/**
 * `--platform` option for Android-only CLI subcommands.
 * Accepts both platforms at parse time so that passing `--platform ios` yields
 * exit code 5 ([ExitCode.PlatformNotSupported]) instead of Clikt's generic
 * exit code 2 ([ExitCode.UsageError]).
 *
 * Commands using this option must call [requireAndroid] in their `run()` body.
 */
fun CliktCommand.androidOnlyPlatformOption() =
    option("--platform", "-p", help = "Target platform (android only)")
        .choice("android" to Platform.Android, "ios" to Platform.Ios)
        .required()

/**
 * Throws [CliExit] with [ExitCode.PlatformNotSupported] if [platform] is not Android.
 * Call at the start of `run()` in Android-only commands.
 */
fun requireAndroid(platform: Platform, commandName: String) {
    if (platform != Platform.Android) {
        throw CliExit(
            ExitCode.PlatformNotSupported,
            "'$commandName' is only supported on Android."
        )
    }
}
