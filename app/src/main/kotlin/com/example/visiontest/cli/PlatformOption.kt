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
 * Accepts only `"android"`. Passing any other value triggers a Clikt error.
 */
fun CliktCommand.androidOnlyPlatformOption() =
    option("--platform", "-p", help = "Target platform (android only)")
        .choice("android" to Platform.Android)
        .required()
