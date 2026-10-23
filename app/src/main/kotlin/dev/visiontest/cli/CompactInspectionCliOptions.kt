package dev.visiontest.cli

import dev.visiontest.common.CompactInspectionOptions
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

internal class CompactInspectionCliOptions : OptionGroup("Compact inspection") {
    private val compact by option("--compact", help = "Return compact-v1 JSON").flag()
    private val type by option("--type", help = "Exact native class name; requires --compact")
    private val region by option(
        "--region", help = "Center region x,y,width,height in native units; requires --compact"
    )
    private val includeBounds by option("--include-bounds", help = "Keep bounds and centers; requires --compact").flag()

    fun options(): CompactInspectionOptions = CompactInspectionOptions(compact, type, region, includeBounds)
}
