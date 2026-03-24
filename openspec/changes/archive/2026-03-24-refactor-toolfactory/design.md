## Context

`ToolFactory.kt` (1975 lines) is the sole class responsible for registering all 36 MCP tools, managing timeouts/errors, extracting request parameters, discovering platform assets (APKs, Xcode projects, xctestrun bundles), and resolving project paths. Every tool follows the same boilerplate: `server.addTool(…) { try { runWithTimeout { … } } catch { handleToolError(…) } }`. The file is still functional but increasingly painful to navigate and extend.

The codebase already follows a clean package structure (`android/`, `ios/`, `config/`, `common/`, `utils/`), so introducing `tools/` and `discovery/` packages is a natural extension.

## Goals / Non-Goals

**Goals:**
- Split `ToolFactory.kt` into cohesive, single-responsibility files under 500 lines each
- Eliminate the repeated try/timeout/catch boilerplate from every tool registration via a DSL
- Eliminate the repeated `request.arguments["x"]?.jsonPrimitive?.content` parameter extraction via extension helpers
- Make discovery logic independently testable without instantiating a full `ToolFactory`
- Keep the public API identical (`ToolFactory(android, ios, logger).registerAllTools(server)`)

**Non-Goals:**
- Changing any MCP tool name, description, schema, or behavior
- Introducing new library dependencies
- Refactoring the `AutomationClient`, `IOSAutomationClient`, or other existing classes
- Introducing dependency injection frameworks (Koin, Dagger, etc.)
- Changing the coroutine strategy (`runBlocking` + `withTimeout`)

## Decisions

### 1. ToolScope DSL over extension functions

**Decision:** Create a `ToolScope` class that wraps `Server` and provides a `tool()` function absorbing the boilerplate.

**Alternatives considered:**
- **Extension functions on Server**: Would require making `ToolFactory` members `internal`, and the error handling + timeout logic would need to be passed as parameters or duplicated. Less encapsulated.
- **Abstract base class**: Kotlin prefers composition over inheritance; a base class would force a single inheritance chain on registrars.

**Rationale:** `ToolScope` encapsulates `Server`, `Logger`, and default timeout in one object. The `tool()` function provides a clean DSL where each tool only supplies name, description, schema, and the actual logic. The boilerplate (try/catch, `runBlocking`, `withTimeout`, `CallToolResult` wrapping) lives in exactly one place.

### 2. ToolRegistrar interface with 4 implementations

**Decision:** One interface, four classes split by platform × responsibility.

| Class | Tools | Primary dependency |
|-------|-------|--------------------|
| `AndroidDeviceToolRegistrar` | 4 | `DeviceConfig` |
| `AndroidAutomationToolRegistrar` | 14 | `AutomationClient`, `ToolDiscovery` |
| `IOSDeviceToolRegistrar` | 4 | `DeviceConfig` |
| `IOSAutomationToolRegistrar` | 10 | `IOSAutomationClient`, `ToolDiscovery` |

**Alternatives considered:**
- **2 classes (Android + iOS)**: The automation registrars are ~500 lines; combining device + automation would still be ~600+ lines per platform.
- **1 class per tool**: 36 files is too granular — the registration logic per tool is only 10-15 lines with the DSL.

**Rationale:** 4 registrars hit the sweet spot: each file is 120-500 lines, each has clear cohesion, and the split matches the existing `android/` vs `ios/` package structure.

### 3. CallToolRequest extension helpers

**Decision:** Add extension functions on `CallToolRequest?` for parameter extraction:

```kotlin
fun CallToolRequest?.requireString(key: String): String
fun CallToolRequest?.requireInt(key: String): Int
fun CallToolRequest?.optionalString(key: String): String?
fun CallToolRequest?.optionalInt(key: String): Int?
```

These return the value or throw `IllegalArgumentException` (for required) which the DSL's catch block maps to an error response.

**Rationale:** The pattern `request?.arguments?.get("x")?.jsonPrimitive?.content ?: return@runWithTimeout "Error: Missing 'x'"` appears dozens of times. Extension helpers reduce each parameter extraction to one line and standardize error messages.

### 4. ToolDiscovery as a standalone class in `discovery/` package

**Decision:** Extract all path-resolution functions to `discovery/ToolDiscovery.kt` as a class taking only `Logger`.

**Alternatives considered:**
- **Top-level functions**: Would work but loses the ability to inject a logger and would scatter functions across the package.
- **Object singleton**: Can't inject dependencies (logger, test overrides for `System.getenv`).

**Rationale:** `ToolDiscovery(logger)` is independently constructable and testable. Tests no longer need mock `DeviceConfig` objects. The class groups 12 related functions that all do filesystem/environment resolution.

### 5. ToolHelpers as an object (not a class)

**Decision:** `extractProperty`, `extractPattern`, `formatAppInfo` become functions on a `ToolHelpers` object.

**Rationale:** These are pure functions with zero state. A Kotlin `object` is the idiomatic way to namespace stateless utilities. Tests call `ToolHelpers.extractProperty(…)` directly — no construction needed.

### 6. iOS process state stays in IOSAutomationToolRegistrar

**Decision:** The `@Volatile var iosXcodebuildProcess: Process?` and related functions (`startAndPollServer`, `buildXcodebuildCommand`, `shellQuote`) move into `IOSAutomationToolRegistrar` as private members.

**Rationale:** This state is only used by iOS automation tools (start/stop server). Keeping it co-located with the tools that use it maintains cohesion.

## Risks / Trade-offs

**[Risk] DSL hides control flow** → The `tool()` function absorbs try/catch and timeout, making it less obvious what happens on error. Mitigation: `ToolScope` is small (~40 lines) and well-documented. Developers read it once and understand all tools.

**[Risk] Visibility changes for discovery functions** → Functions currently `private` on `ToolFactory` become `internal` on `ToolDiscovery` (for testability). Mitigation: They were already `internal` in several cases; the `discovery` package boundary provides logical encapsulation.

**[Risk] Large diff size** → Touching 1975 lines + creating 8 new files + updating 2 test files is a big PR. Mitigation: Tasks are ordered so each can be a separate commit. The refactor is mechanical — no logic changes, just moving code and removing boilerplate.

**[Trade-off] ToolFactory constructor unchanged but internals completely different** → Anyone who was directly calling `internal` functions on `ToolFactory` (only tests) needs to update imports. Mitigation: Only 2 test files are affected, and the migration is straightforward.
