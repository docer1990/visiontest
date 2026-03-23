## Why

`ToolFactory.kt` is 1975 lines — the largest file in the codebase. It tangles three distinct concerns: tool registration (36+ tools with identical boilerplate), path/asset discovery, and string-parsing helpers. Every new tool added grows this monolith, and testing individual tool groups requires instantiating the entire factory with stubs for both platforms. Splitting it now keeps complexity manageable before more tools are added.

## What Changes

- Introduce a `ToolScope` DSL class that absorbs the repeated try/runWithTimeout/catch/handleToolError pattern from every tool registration (~15 lines of boilerplate × 36 tools)
- Introduce a `ToolRegistrar` interface; split tool registrations into 4 platform-specific implementors (AndroidDevice, AndroidAutomation, IOSDevice, IOSAutomation)
- Add `CallToolRequest` extension helpers (`.requireString()`, `.requireInt()`, `.optionalString()`) to eliminate repeated `request.arguments["x"]?.jsonPrimitive?.content` patterns
- Move discovery functions (APK, Xcode project, xctestrun, project root, install dir) to a new `discovery/` package as a `ToolDiscovery` class
- Move pure helper functions (`extractProperty`, `extractPattern`, `formatAppInfo`) to a `tools/ToolHelpers` object
- Reduce `ToolFactory.kt` to a thin coordinator (~30 lines) that wires registrars together
- Migrate existing tests (`ToolFactoryHelpersTest`, `ToolFactoryPathTest`) to test the extracted classes directly

## Capabilities

### New Capabilities
- `tool-registration-dsl`: ToolScope DSL and ToolRegistrar interface that standardize how MCP tools are registered, including timeout handling, error wrapping, and parameter extraction
- `tool-discovery`: Standalone discovery logic for locating APKs, Xcode projects, xctestrun bundles, install directories, and project roots

### Modified Capabilities

_(none — this is a pure refactor with no behavior changes to any MCP tool or discovery logic)_

## Impact

- **Code**: `app/src/main/kotlin/com/example/visiontest/ToolFactory.kt` replaced by ~8 new files across `tools/` and `discovery/` packages
- **Tests**: `ToolFactoryHelpersTest.kt` and `ToolFactoryPathTest.kt` updated with new imports; test classes simplified (no more stub/mock DeviceConfig needed for helpers and discovery)
- **APIs**: Zero change — all MCP tool names, descriptions, schemas, and behavior remain identical
- **Dependencies**: No new library dependencies
- **Entry point**: `Main.kt` unchanged — `ToolFactory(android, ios, logger).registerAllTools(server)` still works
