package com.example.visiontest.tools

object ToolHelpers {

    fun extractProperty(propOutput: String, propName: String): String {
        val regex = Regex("\\[$propName]: \\[(.+?)]")
        return regex.find(propOutput)?.groupValues?.get(1) ?: "Unknown"
    }

    fun formatAppInfo(rawInfo: String, packageName: String): String {
        // Extract useful information using regex patterns
        val versionName = extractPattern(rawInfo, "versionName=(\\S+)")
        val versionCode = extractPattern(rawInfo, "versionCode=(\\d+)")
        val firstInstallTime = extractPattern(rawInfo, "firstInstallTime=(\\S+)")
        val lastUpdateTime = extractPattern(rawInfo, "lastUpdateTime=(\\S+)")
        val targetSdk = extractPattern(rawInfo, "targetSdk=(\\d+)")
        val minSdk = extractPattern(rawInfo, "minSdk=(\\d+)")

        // Extract permissions
        val permissions = Regex("grantedPermissions:(.*?)(?=\\n\\n)", RegexOption.DOT_MATCHES_ALL)
            .find(rawInfo)?.groupValues?.get(1)
            ?.split("\n")
            ?.filter { it.contains("permission.") }
            ?.map { it.trim() }
            ?.take(10) // Limit to first 10 permissions
            ?.joinToString("\n  - ") ?: "None"

        return """
        |App Information for $packageName:
        |-------------------------
        |Version: $versionName (Code: $versionCode)
        |SDK: Target=$targetSdk, Minimum=$minSdk
        |Installation:
        |  - First Installed: $firstInstallTime
        |  - Last Updated: $lastUpdateTime
        |
        |Key Permissions (first 10):
        |  - $permissions
        |${if (permissions != "None") "\n[Additional permissions omitted for brevity]" else ""}
    """.trimMargin()
    }

    fun extractPattern(text: String, pattern: String): String {
        return Regex(pattern).find(text)?.groupValues?.get(1) ?: "Unknown"
    }
}
