plugins {
    alias(libs.plugins.kotlin.jvm)

    // Application plugin for the CLI/MCP entry point (`./gradlew run`).
    application

    // Fat JAR with all dependencies -> app/build/libs/visiontest.jar
    id("com.gradleup.shadow") version "8.3.8"
}

// Single source of truth for the release version: stamped into the JAR manifest
// as Implementation-Version and read at runtime by VersionInfo (MCP server info
// and the CLI --version flag).
version = "0.1.1"

repositories {
    mavenCentral()
    google()  // Required by some Android-related dependencies (adam)
}

val mcpVersion = "0.4.0"
val slf4jVersion = "2.0.17"
val coroutinesVersion = "1.10.1"

dependencies {
    // Use the Kotlin JUnit 5 integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")

    // Use the JUnit 5 integration.
    testImplementation(libs.junit.jupiter.engine)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Coroutine test support (virtual time, TestCoroutineScheduler)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")

    // MockWebServer for HTTP client testing
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // MockK for mocking dependencies in unit tests
    testImplementation("io.mockk:mockk:1.13.10")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-io:0.1.16")

    // MCP SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:$mcpVersion")
    implementation("org.slf4j:slf4j-simple:$slf4jVersion")

    // kotlinx-serialization runtime, used for parsing simctl JSON output
    // (no @Serializable classes, so the compiler plugin is not needed)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Adam - ADB client for Kotlin
    implementation("com.malinskiy.adam:adam:0.5.0")

    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // Clikt — CLI argument parsing for the `visiontest <subcommand>` surface.
    // Used only when the JAR is invoked with CLI args; MCP stdio path does not touch clikt.
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

application {
    // Define the main class for the application.
    mainClass = "com.example.visiontest.MainKt"
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests. Exclude the "e2e" tag: those tests spawn
    // the assembled fat JAR as a subprocess and run via the dedicated `e2eTest` task.
    useJUnitPlatform {
        excludeTags("e2e")
    }
}

// End-to-end tests: build the shadow JAR, then run the "e2e"-tagged tests against
// the real `java -jar visiontest.jar ...` chain. Kept out of `test` so the pure-JVM
// unit suite stays fast and does not depend on assembling the fat JAR.
tasks.register<Test>("e2eTest") {
    description = "Runs end-to-end tests against the assembled shadow JAR."
    group = "verification"
    dependsOn(tasks.named("shadowJar"))
    shouldRunAfter(tasks.named("test"))

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    useJUnitPlatform {
        includeTags("e2e")
    }
    systemProperty(
        "visiontest.jar",
        layout.buildDirectory.file("libs/visiontest.jar").get().asFile.absolutePath,
    )
}

// Run e2e tests as part of `check` (and therefore `build` and CI). shadowJar is
// already in the `build` graph, so the only added cost is the subprocess runs —
// this is the gate that catches packaging regressions (e.g. the embedded
// instructions resource being dropped from the fat JAR). The fast `test` task is
// unaffected since it does not depend on `check`.
tasks.named("check") {
    dependsOn("e2eTest")
}

// Copy AGENT_INSTRUCTIONS.md from repo root into JAR resources so InitCommand can read it at runtime.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("AGENT_INSTRUCTIONS.md")) {
        rename { "agent-instructions.md" }
    }
}

tasks.withType<Jar> {
    manifest {
        attributes["Implementation-Title"] = "visiontest"
        attributes["Implementation-Version"] = project.version
    }
}

tasks {
    shadowJar {
        archiveBaseName.set("visiontest")
        archiveClassifier.set("")
        archiveVersion.set("")
    }
}
