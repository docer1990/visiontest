package dev.visiontest.cli

/** A caller snapshot, independent of any terminal framework or process-global settings. */
data class CliTerminalDimensions(val width: Int, val height: Int, val outputInteractive: Boolean? = null) {
    init {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "Invalid caller terminal dimensions" }
    }

    companion object {
        const val MAX_DIMENSION = 10_000
    }
}
