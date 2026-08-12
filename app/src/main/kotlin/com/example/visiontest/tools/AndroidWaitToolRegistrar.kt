package com.example.visiontest.tools

import com.example.visiontest.ServerNotRunningException
import com.example.visiontest.android.AndroidElementSelectors
import com.example.visiontest.android.AutomationClient
import com.example.visiontest.config.AutomationConfig
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest

/**
 * Registers the Android synchronization tools: `wait_for_element` and `wait_until_gone`.
 *
 * The polling loop lives in the shared JSON-RPC transport
 * ([com.example.visiontest.common.JsonRpcHttpClient.pollForElement]); this registrar
 * validates parameters and adapts MCP/CLI inputs to it.
 */
class AndroidWaitToolRegistrar(
    private val automationClient: AutomationClient
) : ToolRegistrar {

    override fun registerTools(scope: ToolScope) {
        registerWaitForElement(scope)
        registerWaitUntilGone(scope)
    }

    // ==================== Extracted business logic ====================

    private suspend fun requireServer() {
        if (!automationClient.isServerRunning()) {
            throw ServerNotRunningException("Automation server is not running. Use 'start_automation_server' first.")
        }
    }

    internal suspend fun waitForElement(selectors: AndroidElementSelectors, timeoutMs: Int? = null): String {
        requireServer()
        val timeout = resolveWaitTimeout(timeoutMs)
        requireAnySelector(selectors)
        return automationClient.pollForElement(
            expectGone = false,
            timeoutMs = timeout,
            pollIntervalMs = AutomationConfig.WAIT_POLL_INTERVAL_MS,
            selectorDescription = selectors.describe(),
        ) {
            findElement(selectors)
        }
    }

    internal suspend fun waitUntilGone(selectors: AndroidElementSelectors, timeoutMs: Int? = null): String {
        requireServer()
        val timeout = resolveWaitTimeout(timeoutMs)
        requireAnySelector(selectors)
        return automationClient.pollForElement(
            expectGone = true,
            timeoutMs = timeout,
            pollIntervalMs = AutomationConfig.WAIT_POLL_INTERVAL_MS,
            selectorDescription = selectors.describe(),
        ) {
            findElement(selectors)
        }
    }

    private suspend fun findElement(selectors: AndroidElementSelectors): String = automationClient.findElement(
        text = selectors.text,
        textContains = selectors.textContains,
        resourceId = selectors.resourceId,
        className = selectors.className,
        contentDescription = selectors.contentDescription,
    )

    private fun resolveWaitTimeout(timeoutMs: Int?): Long {
        val timeout = timeoutMs?.toLong() ?: AutomationConfig.WAIT_DEFAULT_TIMEOUT_MS
        require(timeout in 1..AutomationConfig.WAIT_MAX_TIMEOUT_MS) {
            "timeoutMs must be between 1 and ${AutomationConfig.WAIT_MAX_TIMEOUT_MS}, got $timeout"
        }
        return timeout
    }

    private fun requireAnySelector(selectors: AndroidElementSelectors) {
        require(selectors.hasAnySelector()) {
            "At least one selector required (text, textContains, resourceId, className, or contentDescription)"
        }
    }

    private fun selectorsFrom(request: CallToolRequest) = AndroidElementSelectors(
        text = request.optionalString("text"),
        textContains = request.optionalString("textContains"),
        resourceId = request.optionalString("resourceId"),
        className = request.optionalString("className"),
        contentDescription = request.optionalString("contentDescription"),
    )

    // ==================== MCP Tool Registrations ====================

    private fun registerWaitForElement(scope: ToolScope) {
        scope.tool(
            name = "wait_for_element",
            description = """
                Waits for a UI element to appear on the current screen, polling every
                ${AutomationConfig.WAIT_POLL_INTERVAL_MS}ms until it is found or the timeout elapses.
                The automation server must be running first (use start_automation_server).

                PREFER THIS over calling 'find_element' in a loop after taps or navigation —
                one call absorbs the whole wait.

                Provide at least ONE selector (same as find_element):
                - text, textContains, resourceId, className, contentDescription

                OPTIONAL PARAMETERS:
                - timeoutMs: Max wait in milliseconds (default ${AutomationConfig.WAIT_DEFAULT_TIMEOUT_MS},
                  max ${AutomationConfig.WAIT_MAX_TIMEOUT_MS})

                RETURNS: The element info (same shape as find_element) as soon as it appears.
                Fails with a timeout error naming the selectors if it never appears.
            """.trimIndent(),
            timeoutMs = AutomationConfig.WAIT_TOOL_TIMEOUT_MS
        ) { request ->
            waitForElement(selectorsFrom(request), request.optionalInt("timeoutMs"))
        }
    }

    private fun registerWaitUntilGone(scope: ToolScope) {
        scope.tool(
            name = "wait_until_gone",
            description = """
                Waits for a UI element to disappear from the current screen, polling every
                ${AutomationConfig.WAIT_POLL_INTERVAL_MS}ms until it is gone or the timeout elapses.
                The automation server must be running first (use start_automation_server).

                USE CASES: loading spinners, dialogs being dismissed, splash screens.

                Provide at least ONE selector (same as find_element):
                - text, textContains, resourceId, className, contentDescription

                OPTIONAL PARAMETERS:
                - timeoutMs: Max wait in milliseconds (default ${AutomationConfig.WAIT_DEFAULT_TIMEOUT_MS},
                  max ${AutomationConfig.WAIT_MAX_TIMEOUT_MS})

                Fails with a timeout error if the element is still present at the deadline.
                A server failure is reported as an error, never as "gone".
            """.trimIndent(),
            timeoutMs = AutomationConfig.WAIT_TOOL_TIMEOUT_MS
        ) { request ->
            waitUntilGone(selectorsFrom(request), request.optionalInt("timeoutMs"))
        }
    }
}
