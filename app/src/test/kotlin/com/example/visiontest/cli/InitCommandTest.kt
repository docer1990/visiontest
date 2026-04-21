package com.example.visiontest.cli

import com.example.visiontest.cli.commands.InitCommand
import com.github.ajalt.clikt.core.MissingOption
import com.github.ajalt.clikt.core.UsageError
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertContains

class InitCommandTest {

    private val fakeInstructions = "# Fake Instructions\nSome content here."

    private fun createCommand(tmpDir: java.nio.file.Path) = InitCommand(
        workingDir = tmpDir,
        resourceLoader = { fakeInstructions },
    )

    // --- Task 1.2: resource loads from classpath ---

    @Test
    fun `embedded resource loads from classpath`() {
        val content = InitCommand.loadClasspathResource("agent-instructions.md")
        assertTrue(content != null && content.contains("VisionTest"), "Resource should contain VisionTest content")
    }

    // --- Task 3.1: single agent writes correct file ---

    @Test
    fun `single agent writes SKILL file to correct path`() {
        val tmp = createTempDirectory("init-test")
        val cmd = createCommand(tmp)
        cmd.parse(listOf("--agent", "claude"))

        val file = tmp.resolve(".claude/skills/visiontest/SKILL.md")
        assertTrue(file.exists(), "SKILL.md should be created")
        val content = file.readText()
        assertContains(content, "---")
        assertContains(content, "name: visiontest")
        assertContains(content, fakeInstructions)
    }

    // --- Task 3.2: multiple agents ---

    @Test
    fun `comma-separated agents writes all files`() {
        val tmp = createTempDirectory("init-test")
        val cmd = createCommand(tmp)
        cmd.parse(listOf("--agent", "claude,opencode,codex"))

        for ((agent, path) in InitCommand.AGENT_PATHS) {
            assertTrue(tmp.resolve(path).exists(), "SKILL.md should exist for $agent")
        }
    }

    // --- Task 3.3: invalid agent name ---

    @Test
    fun `invalid agent name produces UsageError`() {
        val tmp = createTempDirectory("init-test")
        val cmd = createCommand(tmp)
        val ex = assertFailsWith<UsageError> {
            cmd.parse(listOf("--agent", "gemini"))
        }
        assertContains(ex.message ?: "", "Unknown agent")
    }

    // --- Task 3.4: missing --agent ---

    @Test
    fun `missing agent flag produces MissingOption`() {
        val tmp = createTempDirectory("init-test")
        val cmd = createCommand(tmp)
        assertFailsWith<MissingOption> {
            cmd.parse(emptyList())
        }
    }

    // --- Task 3.5: idempotent overwrite ---

    @Test
    fun `running init twice produces same content`() {
        val tmp = createTempDirectory("init-test")
        val cmd1 = createCommand(tmp)
        cmd1.parse(listOf("--agent", "claude"))
        val first = tmp.resolve(".claude/skills/visiontest/SKILL.md").readText()

        val cmd2 = createCommand(tmp)
        cmd2.parse(listOf("--agent", "claude"))
        val second = tmp.resolve(".claude/skills/visiontest/SKILL.md").readText()

        assertEquals(first, second)
    }

    // --- Agent path mapping ---

    @Test
    fun `agent paths map correctly`() {
        assertEquals(".claude/skills/visiontest/SKILL.md", InitCommand.AGENT_PATHS["claude"])
        assertEquals(".opencode/skills/visiontest/SKILL.md", InitCommand.AGENT_PATHS["opencode"])
        assertEquals(".agents/skills/visiontest/SKILL.md", InitCommand.AGENT_PATHS["codex"])
    }
}
