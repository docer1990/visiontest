package dev.visiontest.daemon

import dev.visiontest.android.callerAdbExecutable
import dev.visiontest.android.parseAdbDevices
import dev.visiontest.NoDeviceAvailableException
import dev.visiontest.cli.CliExecutionContext
import dev.visiontest.cli.CliExit
import dev.visiontest.cli.ExitCode
import dev.visiontest.cli.Platform
import dev.visiontest.ios.parseIosSimulatorDevices
import dev.visiontest.common.RequestDeadline
import dev.visiontest.recording.JvmRecordingProcessRunner
import dev.visiontest.performance.TraceStage
import dev.visiontest.performance.TraceRecorder

/** Fresh topology admission, not an identity handshake with the fixed native endpoints. */
internal class DaemonTargetGuard(
    private val androidInventory: suspend () -> List<String> = {
        try {
            parseAdbDevices(adbCommand("devices")).map { it.id }
        } catch (_: NoDeviceAvailableException) {
            emptyList()
        }
    },
    private val androidForwards: suspend () -> String = { adbCommand("forward", "--list") },
    private val iosInventory: suspend () -> List<String> = {
        parseIosSimulatorDevices(command("xcrun", "simctl", "list", "devices", "available", "--json"))
            .filter { it.state == "Booted" }.map { it.id }
    },
    private val environment: () -> Map<String, String> = { CliExecutionContext.current().environment },
) {
    data class Target(val platform: Platform, val id: String)

    suspend fun check(platform: Platform, operation: String): Target? {
        if (platform == Platform.Android) checkAdbRouting()
        // Diagnostics and owned iOS teardown are meaningful even after disconnection.
        if (operation in DIAGNOSTICS || platform == Platform.Ios && operation == "stop_automation_server") return null
        val inventory = when (platform) {
            Platform.Android -> androidInventory()
            Platform.Ios -> iosInventory()
        }.distinct()
        if (inventory.isEmpty()) throw CliExit(ExitCode.DeviceNotFound,
            "Daemon requires one ${label(platform)}; none available")
        if (inventory.size != 1) throw CliExit(ExitCode.GenericFailure,
            "Daemon requires exactly one ${label(platform)}; found ${inventory.size}: ${inventory.joinToString()}")
        val target = Target(platform, inventory.single())
        if (platform == Platform.Android) {
            val requested = environment()["ANDROID_SERIAL"]?.takeIf { it.isNotBlank() }
            if (requested != null && requested != target.id) unsupportedRouting(
                "ANDROID_SERIAL=$requested differs from admitted serial ${target.id}")
            checkForwards(target)
        }
        return target
    }

    private fun checkAdbRouting() {
        val propertyPort = System.getProperty("ANDROID_ADB_SERVER_PORT")
        if (propertyPort != null && propertyPort != "5037") {
            unsupportedRouting("JVM property ANDROID_ADB_SERVER_PORT=$propertyPort")
        }
        val values = environment()
        val allowed = mapOf(
            "ADB_SERVER_SOCKET" to setOf("tcp:localhost:5037", "tcp:127.0.0.1:5037", "tcp:[::1]:5037", "tcp:5037"),
            "ANDROID_ADB_SERVER_PORT" to setOf("5037"),
            "ANDROID_ADB_SERVER_ADDRESS" to setOf("localhost", "127.0.0.1", "::1"),
            "ADB_SERVER_HOST" to setOf("localhost", "127.0.0.1", "::1"),
        )
        for ((name, supported) in allowed) {
            val value = values[name] ?: continue
            if (value !in supported) unsupportedRouting("$name=$value")
        }
    }

    private fun unsupportedRouting(detail: String): Nothing = throw CliExit(ExitCode.GenericFailure,
        "Daemon supports local ADB at localhost:5037; incompatible $detail. " +
            "Unset the override or use --standalone for this configuration.")

    private suspend fun checkForwards(target: Target) {
        val owners = androidForwards().lineSequence().map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= FORWARD_FIELDS && it[1] == "tcp:9008" }.map { it[0] }.toSet()
        if (owners.any { it != target.id }) throw CliExit(ExitCode.GenericFailure,
            "ADB tcp:9008 forward belongs to ${owners.joinToString()}, but admitted Android serial is ${target.id}")
    }

    private fun label(platform: Platform) = when (platform) {
        Platform.Android -> "usable Android device"
        Platform.Ios -> "booted iOS simulator"
    }

    private companion object {
        const val FORWARD_FIELDS = 3
        val DIAGNOSTICS = setOf("available_device", "automation_server_status")

        suspend fun adbCommand(vararg args: String): String =
            command(callerAdbExecutable(), "-H", "localhost", "-P", "5037", *args)

        const val INVENTORY_TIMEOUT_MS = 5000L

        suspend fun command(vararg args: String): String = TraceRecorder.Contextual.span(
            if (args.first() == "xcrun") TraceStage.SIMCTL else TraceStage.ADB,
        ) {
            val result = JvmRecordingProcessRunner().run(args.toList(), RequestDeadline(INVENTORY_TIMEOUT_MS))
            if (result.exitCode != 0) throw CliExit(ExitCode.GenericFailure,
                "Daemon target inventory failed: ${result.stderr}")
            result.stdout.decodeToString().trim()
        }
    }
}
