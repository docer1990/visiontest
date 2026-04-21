package com.example.visiontest.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * `visiontest init --agent claude,opencode,codex`
 *
 * Writes a project-level SKILL.md for each specified agent so that AI coding
 * assistants discover VisionTest CLI instructions automatically.
 *
 * Does NOT require `--platform` because this is not a device operation.
 */
class InitCommand(
    private val workingDir: Path = Path.of(System.getProperty("user.dir")),
    private val resourceLoader: (String) -> String? = Companion::loadClasspathResource,
) : CliktCommand(name = "init", help = "Set up AI agent skill files in the current project") {

    companion object {
        private const val RESOURCE_PATH = "agent-instructions.md"

        /** Agent name → relative SKILL.md path from project root. */
        val AGENT_PATHS: Map<String, String> = mapOf(
            "claude" to ".claude/skills/visiontest/SKILL.md",
            "opencode" to ".opencode/skills/visiontest/SKILL.md",
            "codex" to ".agents/skills/visiontest/SKILL.md",
        )

        private val YAML_FRONTMATTER = """
            |---
            |name: visiontest
            |description: VisionTest mobile automation CLI – commands, workflows, and examples for automating Android and iOS devices.
            |---
            |
        """.trimMargin()

        fun loadClasspathResource(name: String): String? =
            InitCommand::class.java.classLoader.getResourceAsStream(name)
                ?.bufferedReader()
                ?.use { it.readText() }
    }

    private val agents by option("--agent", help = "Comma-separated agent names: claude, opencode, codex")
        .split(",")
        .required()

    override fun run() {
        // Validate agent names
        val invalid = agents.filter { it.isBlank() || it !in AGENT_PATHS }
        if (invalid.isNotEmpty()) {
            throw UsageError(
                "Unknown agent(s): ${invalid.joinToString()}. Valid agents: ${AGENT_PATHS.keys.joinToString()}"
            )
        }

        val instructions = resourceLoader(RESOURCE_PATH)
            ?: throw CliktError("Internal error: embedded resource '$RESOURCE_PATH' not found in JAR")

        val content = YAML_FRONTMATTER + instructions

        for (agent in agents) {
            val relativePath = AGENT_PATHS.getValue(agent)
            val target = workingDir.resolve(relativePath)
            target.parent.createDirectories()
            target.writeText(content)
            echo("  wrote $target")
        }

        echo("Initialized ${agents.size} agent skill file(s).")
    }
}
