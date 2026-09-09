package com.example.visiontest.cli

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.option

internal class IosAppScopeOptions : OptionGroup("iOS app scope") {
    val bundleId by option("--bundle-id", help = "Target app bundle ID on iOS; omit only for Springboard")

    fun validate(platform: Platform) {
        require(platform == Platform.Ios || bundleId == null) { "--bundle-id is only supported on iOS" }
        require(bundleId?.isNotBlank() != false) { "--bundle-id must not be blank" }
    }
}
