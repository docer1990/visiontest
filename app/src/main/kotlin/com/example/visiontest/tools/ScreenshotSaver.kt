package com.example.visiontest.tools

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Shared screenshot pipeline for the Android and iOS automation tool registrars.
 *
 * Parses the JSON-RPC envelope returned by the automation server's `ui.screenshot`
 * method, decodes the base64 PNG payload, and writes it atomically to the resolved
 * target path. Only the platform-specific wording differs between Android and iOS,
 * so it is injected via the constructor.
 */
internal class ScreenshotSaver(
    /** Platform name used in messages, e.g. "Android" or "iOS". */
    private val platformLabel: String,
    /** Default filename prefix, e.g. "android_screenshot". */
    private val filePrefix: String,
    /** The installable artifact for this platform's server, e.g. "APK" or "bundle". */
    private val artifactLabel: String,
) {
    companion object {
        /** Standard JSON-RPC 2.0 error code for an unknown method. */
        private const val JSON_RPC_METHOD_NOT_FOUND = -32601
    }

    private val outdatedArtifact =
        "outdated $platformLabel automation server $artifactLabel — rebuild from source or update the installed $artifactLabel."

    /**
     * Fetches a screenshot response via [fetchResponse], validates the JSON-RPC envelope,
     * and writes the decoded PNG to [outputPath] (or the default timestamped path).
     *
     * Returns a user-facing result string (success or a specific error message).
     */
    suspend fun capture(outputPath: String?, fetchResponse: suspend () -> String): String {
        val response = fetchResponse()
        val root = try {
            JsonParser.parseString(response).asJsonObject
        } catch (e: Exception) {
            return "Screenshot failed: unable to parse response from $platformLabel automation server (${e.message})."
        }

        // JSON-RPC 2.0 envelope: either `result` OR `error` is present at the top level.
        // Check `error` first so we can surface the server's message and map `methodNotFound`
        // to the outdated-artifact guidance (older servers won't know about `ui.screenshot`).
        val errorElement = root.get("error")
        if (errorElement != null && !errorElement.isJsonNull) {
            if (errorElement.isJsonObject) {
                val errorObj = errorElement.asJsonObject
                val codeElement = errorObj.get("code")
                val code = if (codeElement?.isJsonPrimitive == true && codeElement.asJsonPrimitive.isNumber) {
                    codeElement.asInt
                } else null
                val messageElement = errorObj.get("message")
                val message = if (messageElement?.isJsonPrimitive == true && messageElement.asJsonPrimitive.isString) {
                    messageElement.asString
                } else "unknown error"
                if (code == JSON_RPC_METHOD_NOT_FOUND) {
                    return "Screenshot failed: the $platformLabel automation server does not recognize 'ui.screenshot' " +
                        "(JSON-RPC methodNotFound). This indicates an $outdatedArtifact"
                }
                return if (code != null) {
                    "Screenshot failed: $platformLabel automation server returned error ($code): $message"
                } else {
                    "Screenshot failed: $platformLabel automation server returned an error: $message"
                }
            }
            return "Screenshot failed: $platformLabel automation server returned a malformed error envelope."
        }

        val resultElement = root.get("result")
        if (resultElement == null || resultElement.isJsonNull) {
            return "Screenshot failed: response missing 'result' object."
        }
        if (!resultElement.isJsonObject) {
            return "Screenshot failed: response 'result' is not a JSON object."
        }
        val result = resultElement.asJsonObject

        val successElement = result.get("success")
        if (successElement == null || successElement.isJsonNull || !successElement.isJsonPrimitive) {
            return "Screenshot failed: response 'result' has a missing or non-primitive 'success' field."
        }
        val successPrimitive = successElement.asJsonPrimitive
        if (!successPrimitive.isBoolean) {
            return "Screenshot failed: response 'result.success' is not a boolean (got: $successElement)."
        }
        if (!successPrimitive.asBoolean) {
            val serverError = result.get("error")
            val error = if (serverError != null && !serverError.isJsonNull && serverError.isJsonPrimitive && serverError.asJsonPrimitive.isString) {
                serverError.asString
            } else {
                "unknown error"
            }
            return "Screenshot failed on the $platformLabel automation server: $error"
        }

        val pngBase64Element = result.get("pngBase64")
        if (pngBase64Element == null || pngBase64Element.isJsonNull) {
            return "Screenshot failed: response missing 'pngBase64'. This may indicate an $outdatedArtifact"
        }
        if (!pngBase64Element.isJsonPrimitive || !pngBase64Element.asJsonPrimitive.isString) {
            return "Screenshot failed: response 'result.pngBase64' is not a string (got: $pngBase64Element)."
        }
        val pngBase64 = pngBase64Element.asString
        if (pngBase64.isEmpty()) {
            return "Screenshot failed: response missing 'pngBase64'. This may indicate an $outdatedArtifact"
        }

        return writeScreenshot(resolveScreenshotPath(outputPath), pngBase64)
    }

    /**
     * Resolves the target file: [outputPath] verbatim when provided, otherwise a
     * timestamped default under `screenshots/` relative to the MCP server's working
     * directory (the user's current project when launched by a coding agent, not
     * the visiontest install dir).
     */
    fun resolveScreenshotPath(outputPath: String?): File {
        if (outputPath != null && outputPath.isNotBlank()) {
            return File(outputPath).absoluteFile
        }
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return File("screenshots/${filePrefix}_$timestamp.png").absoluteFile
    }

    /**
     * Decodes the base64 PNG and writes it atomically to [target].
     * Runs on Dispatchers.IO so we don't block the tool handler's coroutine context.
     * Writes to a sibling temp file first, then moves into place so a failure or cancellation
     * mid-write cannot leave a partial PNG at [target].
     *
     * Returns a user-facing result string (success or a specific error message).
     */
    suspend fun writeScreenshot(target: File, pngBase64: String): String = withContext(Dispatchers.IO) {
        val bytes = try {
            Base64.getDecoder().decode(pngBase64)
        } catch (e: IllegalArgumentException) {
            return@withContext "Screenshot failed: $platformLabel automation server returned invalid base64 PNG data (${e.message})."
        }

        val targetPath = target.toPath()
        val parentDir = target.parentFile
            ?: return@withContext "Screenshot failed: cannot determine parent directory for ${target.absolutePath}."

        try {
            Files.createDirectories(parentDir.toPath())
        } catch (e: IOException) {
            return@withContext "Screenshot failed: unable to create parent directory ${parentDir.absolutePath} (${e.message})."
        }

        val tempFile = try {
            Files.createTempFile(parentDir.toPath(), ".${filePrefix}_", ".png.tmp")
        } catch (e: IOException) {
            return@withContext "Screenshot failed: unable to create temp file in ${parentDir.absolutePath} (${e.message})."
        }

        try {
            Files.write(tempFile, bytes)
            // ATOMIC_MOVE isn't guaranteed across filesystems, but tempFile is a sibling of
            // target so they're on the same FS. Fall back to plain replace on rare failures.
            try {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
            "Screenshot saved to ${target.absolutePath}"
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tempFile) }
            "Screenshot failed: unable to write PNG to ${target.absolutePath} (${e.message})."
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tempFile) }
            throw e
        }
    }
}
