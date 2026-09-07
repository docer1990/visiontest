package com.example.visiontest.cli

typealias CliCommandRunner = (suspend () -> String) -> Unit
