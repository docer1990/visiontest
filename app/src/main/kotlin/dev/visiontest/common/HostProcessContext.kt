package dev.visiontest.common

import dev.visiontest.cli.CliExecutionContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Creates host commands without inheriting a daemon's directory, PATH, or environment. */
internal object HostProcessContext {
    fun builder(command: List<String>): ProcessBuilder {
        val context = CliExecutionContext.currentOrNull() ?: return ProcessBuilder(command)
        require(command.isNotEmpty()) { "Host command must not be empty" }
        val executable = executable(command.first(), context)
        return ProcessBuilder(listOf(executable) + command.drop(1)).apply {
            directory(context.workingDirectory.toFile())
            environment().clear()
            environment().putAll(context.environment)
        }
    }

    private fun executable(name: String, context: CliExecutionContext): String {
        if (Path.of(name).isAbsolute || File.separatorChar in name) return context.resolve(name).toString()
        // Let ProcessBuilder use its platform default search path when PATH is absent.
        val path = context.environmentValue("PATH")
        return if (path == null) name else path.split(File.pathSeparatorChar).asSequence()
            .map { context.resolve(it).resolve(name) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }?.toString()
            ?: throw IOException("Executable not found in caller PATH: $name")
    }
}
