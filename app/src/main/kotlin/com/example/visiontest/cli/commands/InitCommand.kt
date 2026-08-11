package com.example.visiontest.cli.commands

import com.example.visiontest.cli.CliExit
import com.example.visiontest.cli.ExitCode
import com.example.visiontest.cli.runCliCommand
import com.github.ajalt.clikt.core.CliktCommand
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

    override fun run() = runCliCommand { writeSkillFiles(agents) }

    /**
     * Validates [requestedAgents], writes a SKILL.md per agent under [workingDir], and
     * returns the combined CLI output. Throws [CliExit] with the proper exit code on bad
     * input. Exposed (internal) so tests can exercise it via `executeCliCommand` without
     * tripping the `exitProcess` in [runCliCommand].
     */
    internal fun writeSkillFiles(requestedAgents: List<String>): String {
        val requested = requestedAgents.map { it.trim() }

        val hasBlank = requested.any { it.isBlank() }
        val unknown = requested.filter { it.isNotBlank() && it !in AGENT_PATHS }
        if (hasBlank || unknown.isNotEmpty()) {
            val problems = buildList {
                if (hasBlank) add("blank agent name in --agent list")
                if (unknown.isNotEmpty()) add("Unknown agent(s): ${unknown.joinToString()}")
            }
            throw CliExit(
                ExitCode.UsageError,
                "${problems.joinToString("; ")}. Valid agents: ${AGENT_PATHS.keys.joinToString()}",
            )
        }

        val instructions = resourceLoader(RESOURCE_PATH)
            ?: throw CliExit(
                ExitCode.GenericFailure,
                "Internal error: embedded resource '$RESOURCE_PATH' not found in JAR",
            )
        val content = YAML_FRONTMATTER + instructions

        return buildString {
            for (agent in requested) {
                val target = workingDir.resolve(AGENT_PATHS.getValue(agent))
                target.parent.createDirectories()
                target.writeText(content)
                appendLine("  wrote $target")
            }
            append("Initialized ${requested.size} agent skill file(s).")
        }
    }
}
