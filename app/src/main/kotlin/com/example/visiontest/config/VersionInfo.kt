package com.example.visiontest.config

/**
 * Resolves the application version at runtime.
 *
 * The Gradle build stamps `Implementation-Version` (from `project.version`) into the
 * JAR manifest; when running from classes (tests, IDE) there is no manifest, so the
 * version falls back to "dev". This keeps the version defined in exactly one place
 * (app/build.gradle.kts) instead of being hardcoded in source.
 */
object VersionInfo {
    val version: String = VersionInfo::class.java.`package`?.implementationVersion ?: "dev"
}
