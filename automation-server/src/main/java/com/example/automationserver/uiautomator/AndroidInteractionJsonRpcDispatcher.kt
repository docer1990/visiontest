@file:Suppress("MatchingDeclarationName")

package com.example.automationserver.uiautomator

import com.google.gson.JsonObject

internal interface AndroidInteractionJsonRpcOperations {
    fun pressKey(keyCode: Int): OperationResult
    fun clearText(): OperationResult
    fun longPress(request: NativeGestureRequest): OperationResult
    fun doubleTap(request: NativeGestureRequest): OperationResult
    fun inputText(request: TargetedInputRequest): OperationResult
}

internal fun dispatchAndroidInteractionMethod(
    method: String,
    params: JsonObject?,
    operations: AndroidInteractionJsonRpcOperations,
): OperationResult? = when (method) {
    "ui.pressKey" -> operations.pressKey(parseKeyRequest(params))
    "ui.clearText" -> {
        requireNoParams(params, method)
        operations.clearText()
    }
    "ui.longPress" -> operations.longPress(parseGestureRequest(params))
    "ui.doubleTap" -> operations.doubleTap(parseGestureRequest(params))
    "ui.inputText" -> operations.inputText(parseTargetedInputRequest(params))
    else -> null
}
