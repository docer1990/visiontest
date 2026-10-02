# PR 59 Fourth Review Fixes Design

## Scope

This change resolves two code-review findings. MCP request helpers currently coerce JSON primitives through their textual content, and Android composed double tap continues after a rejected first tap.

## MCP type validation

The shared `CallToolRequest` helpers in `ToolDsl.kt` will enforce the JSON types declared by MCP tool schemas. Required and optional string helpers will accept only JSON strings. Integer helpers will accept only unquoted JSON integer numbers that fit `Int`. The optional boolean helper will accept only JSON boolean literals. Missing optional arguments will continue to return `null`, and missing required arguments will keep their current missing-parameter failure.

This validation applies to every MCP tool that uses the shared helpers. It intentionally rejects previously coerced values such as `"10"` for an integer, `123` for a string, and `"true"` for a boolean before the registrar or backend is called. Existing valid typed requests keep their behavior.

Tests will cover each helper with valid native JSON primitives and wrong-type primitives. Android and iOS interaction registration tests will prove malformed interaction arguments return an MCP error without invoking their registrar.

## Android composed double tap

`performComposedDoubleTap` will return `false` immediately when the first tap returns `false`. It will neither sleep nor invoke the second tap in that case. When the first tap succeeds, the existing 100 ms delay and second tap behavior remain unchanged.

A focused unit test will record tap and sleep events to prove that a rejected first tap stops the sequence.

## Android JSON-RPC interaction dispatch

The five new Android JSON-RPC methods need executable coverage through the dispatcher boundary. A pure dispatcher in the automation-server main source set will recognize `ui.pressKey`, `ui.clearText`, `ui.longPress`, `ui.doubleTap`, and `ui.inputText`. It will parse each method's parameters with the existing validation functions and invoke a narrow operations interface. Unrecognized methods will return no dispatch result so `JsonRpcServerInstrumented` can retain its existing routes.

`JsonRpcServerInstrumented` will adapt its existing `UiAutomatorBridgeInstrumented` instance to that interface and translate dispatcher validation failures to the existing JSON-RPC invalid-params response. The HTTP server, native operation implementations, and response models will not move.

Unit tests will use a recording fake to verify all five method names, parsed values, and operation selection without an emulator. They will also verify that an unrelated method remains unhandled. Existing parser and bridge tests remain responsible for validation edge cases and native behavior.

## Error handling and compatibility

Type mismatches remain `IllegalArgumentException`s handled by `ToolScope`, so callers receive the existing MCP error-result shape. The change tightens malformed-input handling to match the advertised schemas and native JSON-RPC validation. It does not change valid requests or native wire formats.

## Verification

Implementation will follow plain TDD. Each regression test must fail for the reported reason before the production change. Verification will include focused helper, interaction registration, dispatcher, and Android automation-server tests, followed by `:app:test`, `:app:e2eTest`, `build`, and `git diff --check`.
