package com.example.visiontest.cli

import com.example.visiontest.cli.commands.InitCommand
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end verification of `visiontest init` against the assembled fat JAR.
 *
 * Unlike [InitCommandTest] (which runs on the test classpath), this test spawns
 * `java -jar visiontest.jar init ...` as a real subprocess. It proves the whole
 * distributed chain works: the embedded `agent-instructions.md` resource actually
 * survives the shadowJar merge and is reachable via `getResourceAsStream` from
 * inside the JAR, and the CLI entrypoint routes `init` correctly.
 *
 * Runs only via the `e2eTest` Gradle task (tag "e2e" is excluded from `test`),
 * which builds the shadow JAR and passes its path in the `visiontest.jar` property.
 */
@Tag("e2e")
class InitCommandE2ETest {

    @TempDir
    lateinit var tmp: Path

    private fun jarPath(): Path {
        val prop = System.getProperty("visiontest.jar")
            ?: error("System property 'visiontest.jar' not set; run via the ':app:e2eTest' Gradle task")
        val jar = Path.of(prop)
        assertTrue(jar.exists(), "Shadow JAR not found at $jar; run ':app:shadowJar' first")
        return jar
    }

    private data class Result(val exitCode: Int, val output: String)

    private fun runInit(vararg args: String): Result {
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val proc = ProcessBuilder(javaBin, "-jar", jarPath().toString(), "init", *args)
            .directory(tmp.toFile())
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "init subprocess timed out")
        return Result(proc.exitValue(), output)
    }

    @Test
    fun `init writes SKILL file with embedded instructions from the real JAR`() {
        val result = runInit("--agent", "claude")

        assertEquals(0, result.exitCode, "init should succeed. Output:\n${result.output}")

        val file = tmp.resolve(".claude/skills/visiontest/SKILL.md")
        assertTrue(file.exists(), "SKILL.md should be created. Output:\n${result.output}")

        val content = file.readText()
        assertContains(content, "name: visiontest", message = "frontmatter should be present")
        // Distinctive text from the *body* of AGENT_INSTRUCTIONS.md — proves the
        // embedded resource (not just the hardcoded frontmatter) is inside the JAR.
        assertContains(
            content,
            "Standard Automation Loop",
            message = "embedded instructions body should be bundled in the fat JAR",
        )
    }

    @Test
    fun `init writes all agent files from the real JAR`() {
        val result = runInit("--agent", "claude,opencode,codex")

        assertEquals(0, result.exitCode, "init should succeed. Output:\n${result.output}")
        for ((agent, path) in InitCommand.AGENT_PATHS) {
            assertTrue(tmp.resolve(path).exists(), "SKILL.md should exist for $agent")
        }
    }

    @Test
    fun `invalid agent name exits with usage error code 2`() {
        val result = runInit("--agent", "gemini")

        assertEquals(2, result.exitCode, "invalid agent should exit 2. Output:\n${result.output}")
        assertContains(result.output, "Unknown agent")
    }
}
