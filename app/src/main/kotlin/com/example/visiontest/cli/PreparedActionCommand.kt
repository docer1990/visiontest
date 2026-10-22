package com.example.visiontest.cli

import com.example.visiontest.common.FieldType
import com.example.visiontest.common.automationResult
import com.example.visiontest.common.validateFields
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

abstract class PreparedActionCommand protected constructor(
    name: String,
    help: String,
    runner: CliCommandRunner,
) : PreparedCommand(name, help, runner) {
    protected val jsonOutput by option("--json", help = "Output one unwrapped JSON result object").flag()

    override fun formatStandaloneOutput(response: String): String {
        if (!jsonOutput) return response
        val result = automationResult(response)
        if (!result.isRpcError) {
            result.value.validateFields("success", FieldType.BOOLEAN, required = true)
            result.value.validateFields("error", FieldType.STRING)
        }
        return result.value.toString()
    }
}
