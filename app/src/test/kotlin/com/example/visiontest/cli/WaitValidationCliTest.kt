package com.example.visiontest.cli

import com.example.visiontest.cli.commands.WaitForElementCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WaitValidationCliTest {
    private fun invoke(vararg args: String): Pair<CliResult, Lazy<ComponentHolder>> {
        var result: CliResult? = null
        val components = lazy<ComponentHolder> { error("Invalid wait input initialized device components") }
        val parsed = executeCliCommand {
            WaitForElementCommand(components) { operation ->
                result = executeCliCommand(operation)
            }.parse(args.toList())
            ""
        }
        return (result ?: parsed) to components
    }

    @Test
    fun `invalid waits fail before initializing device components`() {
        val invalidWaits = listOf(
            arrayOf("-p", "android"),
            arrayOf("-p", "android", "--gone"),
            arrayOf("-p", "android", "--text", "Login", "--timeout", "0"),
            arrayOf("-p", "ios", "--text", "Login", "--timeout", "30001"),
            arrayOf("-p", "android", "--text", "Login", "--bundle-id", "com.example.app"),
            arrayOf("-p", "android", "--text", "Login", "--bundle-id", "com.example.app", "--gone"),
            arrayOf("-p", "ios", "--text", "Login", "--bundle-id", " "),
            arrayOf("-p", "ios", "--bundle-id", "com.example.app"),
        )

        for (args in invalidWaits) {
            val (result, components) = invoke(*args)
            assertEquals(2, result.exitCode, args.joinToString(" "))
            assertFalse(components.isInitialized(), args.joinToString(" "))
        }
    }
}
