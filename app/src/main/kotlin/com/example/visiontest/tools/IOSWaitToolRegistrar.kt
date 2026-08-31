package com.example.visiontest.tools

import com.example.visiontest.ServerNotRunningException
import com.example.visiontest.config.IOSAutomationConfig
import com.example.visiontest.ios.IOSAutomationClient
import com.example.visiontest.ios.IOSElementSelectors
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest

/**
 * Registers the iOS synchronization tools: `ios_wait_for_element` and `ios_wait_until_gone`.
 *
 * The polling loop lives in the shared JSON-RPC transport
 * ([com.example.visiontest.common.JsonRpcHttpClient.pollForElement]); this registrar
 * validates parameters and adapts MCP/CLI inputs to it.
 */
class IOSWaitToolRegistrar(
    private val iosAutomationClient: IOSAutomationClient
) : ToolRegistrar {

    override fun registerTools(scope: ToolScope) {
        registerWaitForElement(scope)
        registerWaitUntilGone(scope)
    }

    // ==================== Extracted business logic ====================

    private suspend fun requireServer() {
        if (!iosAutomationClient.isServerRunning()) {
            throw ServerNotRunningException(
                "iOS automation server is not running. Use 'ios_start_automation_server' first."
            )
        }
    }

    internal suspend fun waitForElement(selectors: IOSElementSelectors, timeoutMs: Int? = null): String {
        requireServer()
        val timeout = resolveWaitTimeout(timeoutMs)
        requireAnySelector(selectors)
        return iosAutomationClient.pollForElement(
            expectGone = false,
            timeoutMs = timeout,
            pollIntervalMs = IOSAutomationConfig.WAIT_POLL_INTERVAL_MS,
            selectorDescription = selectors.describe(),
        ) {
            findElement(selectors)
        }
    }

    internal suspend fun waitUntilGone(selectors: IOSElementSelectors, timeoutMs: Int? = null): String {
        requireServer()
        val timeout = resolveWaitTimeout(timeoutMs)
        requireAnySelector(selectors)
        return iosAutomationClient.pollForElement(
            expectGone = true,
            timeoutMs = timeout,
            pollIntervalMs = IOSAutomationConfig.WAIT_POLL_INTERVAL_MS,
            selectorDescription = selectors.describe(),
        ) {
            findElement(selectors)
        }
    }

    private suspend fun findElement(selectors: IOSElementSelectors): String = iosAutomationClient.findElement(
        text = selectors.text,
        textContains = selectors.textContains,
        identifier = selectors.identifier,
        elementType = selectors.elementType,
        label = selectors.label,
        bundleId = selectors.bundleId,
    )

    private fun resolveWaitTimeout(timeoutMs: Int?): Long {
        val timeout = timeoutMs?.toLong() ?: IOSAutomationConfig.WAIT_DEFAULT_TIMEOUT_MS
        require(timeout in 1..IOSAutomationConfig.WAIT_MAX_TIMEOUT_MS) {
            "timeoutMs must be between 1 and ${IOSAutomationConfig.WAIT_MAX_TIMEOUT_MS}, got $timeout"
        }
        return timeout
    }

    private fun requireAnySelector(selectors: IOSElementSelectors) {
        require(selectors.hasAnySelector()) {
            "At least one selector required (text, textContains, resourceId, className, or contentDescription)"
        }
    }

    private fun selectorsFrom(request: CallToolRequest) = IOSElementSelectors(
        text = request.optionalString("text"),
        textContains = request.optionalString("textContains"),
        identifier = request.optionalString("resourceId"),
        elementType = request.optionalString("className"),
        label = request.optionalString("contentDescription"),
        bundleId = request.optionalString("bundleId"),
    )

    // ==================== MCP Tool Registrations ====================

    private fun registerWaitForElement(scope: ToolScope) {
        scope.tool(
            name = "ios_wait_for_element",
            description = """
                Waits for a UI element to appear on the current iOS simulator screen, polling every
                ${IOSAutomationConfig.WAIT_POLL_INTERVAL_MS}ms until it is found or the timeout elapses.
                The iOS automation server must be running first (use ios_start_automation_server).

                PREFER THIS over calling 'ios_find_element' in a loop after taps or navigation —
                one call absorbs the whole wait.

                Provide at least ONE selector (same as ios_find_element):
                - text, textContains, resourceId, className, contentDescription

                OPTIONAL PARAMETERS:
                - bundleId: Bundle ID of the app to search in. ALWAYS provide it when waiting in an app.
                - timeoutMs: Max wait in milliseconds (default ${IOSAutomationConfig.WAIT_DEFAULT_TIMEOUT_MS},
                  max ${IOSAutomationConfig.WAIT_MAX_TIMEOUT_MS})

                RETURNS: The element info (same shape as ios_find_element) as soon as it appears.
                Fails with a timeout error naming the selectors if it never appears.
            """.trimIndent(),
            timeoutMs = IOSAutomationConfig.WAIT_TOOL_TIMEOUT_MS
        ) { request ->
            waitForElement(selectorsFrom(request), request.optionalInt("timeoutMs"))
        }
    }

    private fun registerWaitUntilGone(scope: ToolScope) {
        scope.tool(
            name = "ios_wait_until_gone",
            description = """
                Waits for a UI element to disappear from the current iOS simulator screen, polling every
                ${IOSAutomationConfig.WAIT_POLL_INTERVAL_MS}ms until it is gone or the timeout elapses.
                The iOS automation server must be running first (use ios_start_automation_server).

                USE CASES: loading spinners, sheets being dismissed, splash screens.

                Provide at least ONE selector (same as ios_find_element):
                - text, textContains, resourceId, className, contentDescription

                OPTIONAL PARAMETERS:
                - bundleId: Bundle ID of the app to search in. ALWAYS provide it when waiting in an app.
                - timeoutMs: Max wait in milliseconds (default ${IOSAutomationConfig.WAIT_DEFAULT_TIMEOUT_MS},
                  max ${IOSAutomationConfig.WAIT_MAX_TIMEOUT_MS})

                Fails with a timeout error if the element is still present at the deadline.
                A server failure is reported as an error, never as "gone".
            """.trimIndent(),
            timeoutMs = IOSAutomationConfig.WAIT_TOOL_TIMEOUT_MS
        ) { request ->
            waitUntilGone(selectorsFrom(request), request.optionalInt("timeoutMs"))
        }
    }
}
