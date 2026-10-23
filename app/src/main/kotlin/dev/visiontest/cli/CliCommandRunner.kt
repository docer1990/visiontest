package dev.visiontest.cli

typealias CliCommandRunner = (suspend () -> String) -> Unit
