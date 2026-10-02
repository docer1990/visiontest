package com.example.automationserver.uiautomator

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class AndroidInteractionJsonRpcDispatcherTest {

    @Test
    fun `dispatches Android interaction methods with parsed parameters`() {
        val operations = RecordingOperations()

        assertSame(
            operations.pressKeyResult,
            dispatchAndroidInteractionMethod("ui.pressKey", json("""{"keyCode":66}"""), operations),
        )
        assertEquals(66, operations.keyCode)

        assertSame(
            operations.clearTextResult,
            dispatchAndroidInteractionMethod("ui.clearText", null, operations),
        )
        assertEquals(1, operations.clearTextCalls)

        assertSame(
            operations.longPressResult,
            dispatchAndroidInteractionMethod(
                "ui.longPress",
                json("""{"x":10,"y":20}"""),
                operations,
            ),
        )
        assertEquals(
            NativeGestureTarget.Coordinates(10, 20),
            operations.longPressRequest?.target,
        )

        assertSame(
            operations.doubleTapResult,
            dispatchAndroidInteractionMethod(
                "ui.doubleTap",
                json("""{"resourceId":"menu","timeoutMs":1500}"""),
                operations,
            ),
        )
        val doubleTapTarget = assertIs<NativeGestureTarget.Element>(
            operations.doubleTapRequest?.target,
        )
        assertEquals("menu", doubleTapTarget.selectors.resourceId)
        assertEquals(1_500, doubleTapTarget.timeoutMs)

        assertSame(
            operations.inputTextResult,
            dispatchAndroidInteractionMethod(
                "ui.inputText",
                json("""{"text":"Ada","targetResourceId":"name","timeoutMs":2500}"""),
                operations,
            ),
        )
        assertEquals("Ada", operations.inputTextRequest?.text)
        assertEquals("name", operations.inputTextRequest?.selectors?.resourceId)
        assertEquals(2_500, operations.inputTextRequest?.timeoutMs)
    }

    @Test
    fun `leaves unrelated methods unhandled`() {
        assertNull(
            dispatchAndroidInteractionMethod("ui.dumpHierarchy", null, RecordingOperations()),
        )
    }

    private class RecordingOperations : AndroidInteractionJsonRpcOperations {
        val pressKeyResult = OperationResult(success = false, error = "pressKey")
        val clearTextResult = OperationResult(success = false, error = "clearText")
        val longPressResult = OperationResult(success = false, error = "longPress")
        val doubleTapResult = OperationResult(success = false, error = "doubleTap")
        val inputTextResult = OperationResult(success = false, error = "inputText")

        var keyCode: Int? = null
        var clearTextCalls = 0
        var longPressRequest: NativeGestureRequest? = null
        var doubleTapRequest: NativeGestureRequest? = null
        var inputTextRequest: TargetedInputRequest? = null

        override fun pressKey(keyCode: Int): OperationResult {
            this.keyCode = keyCode
            return pressKeyResult
        }

        override fun clearText(): OperationResult {
            clearTextCalls += 1
            return clearTextResult
        }

        override fun longPress(request: NativeGestureRequest): OperationResult {
            longPressRequest = request
            return longPressResult
        }

        override fun doubleTap(request: NativeGestureRequest): OperationResult {
            doubleTapRequest = request
            return doubleTapResult
        }

        override fun inputText(request: TargetedInputRequest): OperationResult {
            inputTextRequest = request
            return inputTextResult
        }
    }

    private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
}
