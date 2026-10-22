package com.example.visiontest.common

import org.junit.jupiter.api.DynamicTest

internal object CompactInspectionFeatureScenarios {
    fun tests(
        implemented: Map<String, () -> Map<String, () -> Unit>>,
        requireComplete: Boolean = false,
    ): List<DynamicTest> {
        val scenarios = readScenarios()
        require(implemented.isNotEmpty()) { "At least one compact inspection scenario must be registered" }
        require(scenarios.keys.containsAll(implemented.keys)) {
            "Registered compact inspection scenarios are absent from the feature: ${implemented.keys - scenarios.keys}"
        }
        if (requireComplete) require(implemented.keys == scenarios.keys) {
            "Compact inspection scenarios have no implementation: ${scenarios.keys - implemented.keys}"
        }
        return implemented.map { (name, createBindings) ->
            DynamicTest.dynamicTest(name) {
                val steps = scenarios.getValue(name)
                val bindings = createBindings()
                require(bindings.keys.containsAll(steps)) {
                    "Missing step bindings for '$name': ${steps - bindings.keys}"
                }
                steps.forEach { bindings.getValue(it).invoke() }
            }
        }
    }

    private fun readScenarios(): Map<String, List<String>> {
        val resource = checkNotNull(javaClass.getResourceAsStream("/features/compact-inspection.feature")) {
            "Missing features/compact-inspection.feature resource"
        }
        val scenarios = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        resource.bufferedReader().useLines { lines ->
            lines.map(String::trim).forEach { line ->
                when {
                    line.isBlank() || line.startsWith('#') || line.startsWith("Feature:") -> Unit
                    line.startsWith("Scenario:") -> {
                        val name = line.removePrefix("Scenario:").trim()
                        require(name.isNotBlank() && name !in scenarios) { "Invalid or duplicate scenario: $name" }
                        current = mutableListOf<String>().also { scenarios[name] = it }
                    }
                    line.substringBefore(' ') in setOf("Given", "When", "Then", "And") ->
                        checkNotNull(current) { "Step outside a scenario: $line" }.add(line)
                    else -> error("Unsupported compact inspection feature line: $line")
                }
            }
        }
        require(scenarios.values.all { it.isNotEmpty() }) { "Compact inspection scenarios must contain steps" }
        return scenarios
    }
}
