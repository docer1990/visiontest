package com.example.visiontest.tools

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.example.visiontest.performance.TraceRecorder
import com.example.visiontest.performance.TraceStage
import com.example.visiontest.performance.OperationOutcome
import com.example.visiontest.performance.TraceMetric
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
    private val trace: TraceRecorder = TraceRecorder.Disabled,
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
        val parsed = trace.span(TraceStage.SCREENSHOT_PARSE) {
            parseScreenshot(response)
        }
        parsed.failure?.let {
            trace.operationOutcome(OperationOutcome.FAILURE)
            return it
        }
        return writeScreenshot(resolveScreenshotPath(outputPath), checkNotNull(parsed.pngBase64))
    }
    private data class ParsedScreenshot(val pngBase64: String? = null, val failure: String? = null)
    private suspend fun parseFailure(message: String): ParsedScreenshot {
        trace.operationOutcome(OperationOutcome.FAILURE)
        return ParsedScreenshot(failure = message)
    }
    private suspend fun parseScreenshot(response: String): ParsedScreenshot {
        val root = try {
            JsonParser.parseString(response).asJsonObject
        } catch (e: Exception) {
            return parseFailure("Screenshot failed: unable to parse response from $platformLabel automation " +
                "server (${e.message}).")
        }
        val errorElement = root.get("error")
        return if (errorElement != null && !errorElement.isJsonNull) {
            parseError(errorElement)
        } else {
            parseResult(root)
        }
    }
    private suspend fun parseError(errorElement: JsonElement): ParsedScreenshot {

        // JSON-RPC 2.0 envelope: either `result` OR `error` is present at the top level.
        // Check `error` first so we can surface the server's message and map `methodNotFound`
        // to the outdated-artifact guidance (older servers won't know about `ui.screenshot`).
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
            return parseFailure(if (code == JSON_RPC_METHOD_NOT_FOUND) {
                "Screenshot failed: the $platformLabel automation server does not recognize 'ui.screenshot' " +
                    "(JSON-RPC methodNotFound). This indicates an $outdatedArtifact"
            } else if (code != null) {
                "Screenshot failed: $platformLabel automation server returned error ($code): $message"
            } else {
                "Screenshot failed: $platformLabel automation server returned an error: $message"
            })
        }
        return parseFailure("Screenshot failed: $platformLabel automation server returned a malformed error envelope.")
    }
    private suspend fun parseResult(root: JsonObject): ParsedScreenshot {
        val result = root.get("result")
        return when {
            result == null || result.isJsonNull -> parseFailure("Screenshot failed: response missing 'result' object.")
            !result.isJsonObject -> parseFailure("Screenshot failed: response 'result' is not a JSON object.")
            else -> parseSuccess(result.asJsonObject)
        }
    }
    private suspend fun parseSuccess(result: JsonObject): ParsedScreenshot {
        val success = result.get("success")
        return when {
            success == null || success.isJsonNull || !success.isJsonPrimitive ->
                parseFailure("Screenshot failed: response 'result' has a missing or non-primitive 'success' field.")
            !success.asJsonPrimitive.isBoolean ->
                parseFailure("Screenshot failed: response 'result.success' is not a boolean (got: $success).")
            !success.asBoolean -> {
                val serverError = result.get("error")
                val error = if (serverError != null && !serverError.isJsonNull && serverError.isJsonPrimitive && serverError.asJsonPrimitive.isString) {
                    serverError.asString
                } else {
                    "unknown error"
                }
                parseFailure("Screenshot failed on the $platformLabel automation server: $error")
            }
            else -> parsePng(result)
        }
    }
    private suspend fun parsePng(result: JsonObject): ParsedScreenshot {
        val png = result.get("pngBase64")
        return when {
            png == null || png.isJsonNull ->
                parseFailure("Screenshot failed: response missing 'pngBase64'. This may indicate an $outdatedArtifact")
            !png.isJsonPrimitive || !png.asJsonPrimitive.isString ->
                parseFailure("Screenshot failed: response 'result.pngBase64' is not a string (got: $png).")
            png.asString.isEmpty() ->
                parseFailure("Screenshot failed: response missing 'pngBase64'. This may indicate an $outdatedArtifact")
            else -> {
                trace.operationOutcome(OperationOutcome.SUCCESS)
                ParsedScreenshot(pngBase64 = png.asString)
            }
        }
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
        var decodeFailure: String? = null
        val bytes = trace.span(TraceStage.SCREENSHOT_DECODE) {
            try {
                Base64.getDecoder().decode(pngBase64).also {
                    trace.metric(TraceMetric.DECODED_BYTES, it.size.toLong())
                    trace.operationOutcome(OperationOutcome.SUCCESS)
                }
            } catch (e: IllegalArgumentException) {
                trace.operationOutcome(OperationOutcome.FAILURE)
                decodeFailure = "Screenshot failed: $platformLabel automation server returned invalid " +
                    "base64 PNG data (${e.message})."
                null
            }
        } ?: run {
            trace.operationOutcome(OperationOutcome.FAILURE)
            return@withContext checkNotNull(decodeFailure)
        }
        val saved = writeScreenshotBytes(target, bytes)
        trace.operationOutcome(if (saved.success) OperationOutcome.SUCCESS else OperationOutcome.FAILURE)
        saved.message
    }
    private data class SavedScreenshot(val message: String, val success: Boolean)

    private suspend fun writeScreenshotBytes(target: File, bytes: ByteArray): SavedScreenshot =
        trace.span(TraceStage.SCREENSHOT_WRITE) {
            val failure: suspend (String) -> SavedScreenshot = { message ->
                trace.operationOutcome(OperationOutcome.FAILURE)
                SavedScreenshot(message, success = false)
            }
            val targetPath = target.toPath()
            val parentDir = target.parentFile
                ?: return@span failure(
                    "Screenshot failed: cannot determine parent directory for ${target.absolutePath}."
                )

            try {
                Files.createDirectories(parentDir.toPath())
            } catch (e: IOException) {
                return@span failure(
                    "Screenshot failed: unable to create parent directory ${parentDir.absolutePath} (${e.message})."
                )
            }

            val tempFile = try {
                Files.createTempFile(parentDir.toPath(), ".${filePrefix}_", ".png.tmp")
            } catch (e: IOException) {
                return@span failure(
                    "Screenshot failed: unable to create temp file in ${parentDir.absolutePath} (${e.message})."
                )
            }

            try {
                Files.write(tempFile, bytes)
                // The sibling temporary file shares the target filesystem. Preserve the
                // existing fallback when an atomic move is unavailable.
                try {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
                trace.operationOutcome(OperationOutcome.SUCCESS)
                SavedScreenshot("Screenshot saved to ${target.absolutePath}", success = true)
            } catch (e: IOException) {
                runCatching { Files.deleteIfExists(tempFile) }
                failure("Screenshot failed: unable to write PNG to ${target.absolutePath} (${e.message}).")
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(tempFile) }
                throw e
            }
        }
}
