package com.example.visiontest.cli

import com.example.visiontest.cli.commands.InitCommand
import com.github.ajalt.clikt.core.MissingOption
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertContains

/**
 * Unit tests exercise [InitCommand.writeSkillFiles] directly (and error paths through
 * [executeCliCommand]) rather than `cmd.parse(...)`, because `run()` now routes through
 * `runCliCommand`, which calls `exitProcess` and would terminate the test JVM.
 */
class InitCommandTest {

    @TempDir
    lateinit var tmp: Path

    private val fakeInstructions = "# Fake Instructions\nSome content here."

    private fun createCommand(dir: Path = tmp) = InitCommand(
        workingDir = dir,
        resourceLoader = { fakeInstructions },
    )

    // --- resource loads from classpath ---

    @Test
    fun `embedded resource loads from classpath`() {
        val content = InitCommand.loadClasspathResource("agent-instructions.md")
        assertTrue(content != null && content.contains("VisionTest"), "Resource should contain VisionTest content")
    }

    // --- single agent writes correct file ---

    @Test
    fun `single agent writes SKILL file to correct path`() {
        createCommand().writeSkillFiles(listOf("claude"))

        val file = tmp.resolve(".claude/skills/visiontest/SKILL.md")
        assertTrue(file.exists(), "SKILL.md should be created")
        val content = file.readText()
        assertTrue(content.startsWith("---\n"), "SKILL.md should start with YAML frontmatter delimiter")
        assertContains(content, "\n---\n", message = "SKILL.md should have closing frontmatter delimiter")
        assertContains(content, "name: visiontest")
        assertContains(content, fakeInstructions)
    }

    // --- multiple agents ---

    @Test
    fun `comma-separated agents writes all files`() {
        createCommand().writeSkillFiles(listOf("claude", "opencode", "codex"))

        for ((agent, path) in InitCommand.AGENT_PATHS) {
            assertTrue(tmp.resolve(path).exists(), "SKILL.md should exist for $agent")
        }
    }

    // --- whitespace around agent names is trimmed ---

    @Test
    fun `agent names are trimmed`() {
        createCommand().writeSkillFiles(listOf(" claude ", "opencode "))

        assertTrue(tmp.resolve(".claude/skills/visiontest/SKILL.md").exists())
        assertTrue(tmp.resolve(".opencode/skills/visiontest/SKILL.md").exists())
    }

    // --- invalid agent name ---

    @Test
    fun `invalid agent name is a usage error with exit code 2`() {
        val result = executeCliCommand { createCommand().writeSkillFiles(listOf("gemini")) }

        assertEquals(2, result.exitCode)
        assertContains(result.stderr ?: "", "Unknown agent")
    }

    // --- blank agent name in comma list ---

    @Test
    fun `blank agent name is a usage error with a clear message`() {
        val result = executeCliCommand { createCommand().writeSkillFiles(listOf("", "claude")) }

        assertEquals(2, result.exitCode)
        assertContains(result.stderr ?: "", "blank agent name")
    }

    // --- missing --agent (Clikt parse-time, before run/exitProcess) ---

    @Test
    fun `missing agent flag produces MissingOption`() {
        val cmd = createCommand()
        assertFailsWith<MissingOption> {
            cmd.parse(emptyList())
        }
    }

    // --- idempotent overwrite ---

    @Test
    fun `running init twice produces same content`() {
        createCommand().writeSkillFiles(listOf("claude"))
        val first = tmp.resolve(".claude/skills/visiontest/SKILL.md").readText()

        createCommand().writeSkillFiles(listOf("claude"))
        val second = tmp.resolve(".claude/skills/visiontest/SKILL.md").readText()

        assertEquals(first, second)
    }

    // --- agent path mapping ---

    @Test
    fun `agent paths map correctly`() {
        assertEquals(".claude/skills/visiontest/SKILL.md", InitCommand.AGENT_PATHS["claude"])
        assertEquals(".opencode/skills/visiontest/SKILL.md", InitCommand.AGENT_PATHS["opencode"])
        assertEquals(".agents/skills/visiontest/SKILL.md", InitCommand.AGENT_PATHS["codex"])
    }
}
