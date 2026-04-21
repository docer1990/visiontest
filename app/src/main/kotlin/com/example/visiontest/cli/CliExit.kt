package com.example.visiontest.cli

/**
 * Exception thrown by CLI commands to signal a non-zero exit.
 *
 * The [code] is used as the process exit code and the [message] is printed to stderr.
 * A [code] of [ExitCode.Success] should never be thrown — return normally instead.
 */
class CliExit(val code: ExitCode, override val message: String) : Exception(message)

/**
 * Fixed set of CLI exit codes. LLM-scriptable: agents can branch on the numeric code
 * without parsing stderr text.
 */
enum class ExitCode(val value: Int) {
    /** Command completed successfully. */
    Success(0),

    /** Unhandled / unexpected exception. */
    GenericFailure(1),

    /** Missing or invalid flag / argument (clikt usage error). */
    UsageError(2),

    /** The automation server is not running or not reachable. */
    ServerNotReachable(3),

    /** No device or simulator is connected / available. */
    DeviceNotFound(4),

    /** The requested platform is not supported for this command (e.g. `press_back --platform ios`). */
    PlatformNotSupported(5)
}
