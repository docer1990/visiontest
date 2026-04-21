package com.example.visiontest.cli

import com.github.ajalt.clikt.core.NoOpCliktCommand

/**
 * Root command for the `visiontest` CLI. Dispatches to per-operation subcommands.
 *
 * The JAR's `main(args)` enters this command only when invoked with arguments that
 * are not the MCP stdio sentinel (empty args or `serve`). See [com.example.visiontest.main].
 *
 * Subcommands are added here via `.subcommands(...)` — kept empty in this placeholder
 * so Phase 1 compiles. Phase 3 wires in the real subcommand list.
 */
class VisionTestCli : NoOpCliktCommand(name = "visiontest")
