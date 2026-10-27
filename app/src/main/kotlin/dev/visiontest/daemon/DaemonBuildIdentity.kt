package dev.visiontest.daemon

import java.io.IOException

/** Reads a build-time fingerprint without hashing/loading the application's JAR in each client. */
object DaemonBuildIdentity {
    fun load(loader: ClassLoader = DaemonBuildIdentity::class.java.classLoader): String {
        val identity = loader.getResourceAsStream("daemon-build.sha256")?.use {
            it.bufferedReader(Charsets.UTF_8).readText().trim()
        } ?: throw IOException("Missing daemon build identity resource; rebuild VisionTest")
        if (!identity.matches(Regex("[a-f0-9]{64}"))) throw IOException("Invalid daemon build identity resource")
        return identity
    }
}
