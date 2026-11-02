package dev.visiontest.cli

import dev.visiontest.android.Android
import dev.visiontest.ios.IOSSimulator

/** Use the device implementations' format rules before constructing the daemon graph. */
internal fun validateAppIdentifier(platform: Platform, id: String) {
    when (platform) {
        Platform.Android -> require(Android.isValidPackageName(id)) { "Invalid package name: $id" }
        Platform.Ios -> require(IOSSimulator.isValidBundleIdFormat(id)) { "Invalid bundle ID format: $id" }
    }
}
