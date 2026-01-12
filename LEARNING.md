# Learning Notes: VisionTest MCP Server

This document captures key learnings, architectural decisions, and best practices discovered while building this MCP server for mobile automation.

---

## Table of Contents

1. [UIAutomator and the Instrumentation Requirement](#uiautomator-and-the-instrumentation-requirement)
2. [Extracting Shared Code with Template Method Pattern](#extracting-shared-code-with-template-method-pattern)
3. [Android Instrumentation Pattern](#android-instrumentation-pattern)
4. [Security Best Practices](#security-best-practices)
5. [Code Quality Patterns](#code-quality-patterns)
6. [Common Pitfalls](#common-pitfalls)

---

## UIAutomator and the Instrumentation Requirement

### The Problem

Android's UIAutomator API requires a valid `Instrumentation` object to function. This `Instrumentation` provides:

- A connection to the system's `UiAutomation` service
- Permissions to interact with any app on the device
- Access to accessibility APIs needed for UI inspection

### The Challenge

We initially tried to use UIAutomator from a regular Android Service:

```kotlin
// This DOESN'T work!
class UiAutomatorBridge(context: Context) {
    private val uiDevice: UiDevice by lazy {
        val instrumentation = Instrumentation()  // Empty instrumentation
        UiDevice.getInstance(instrumentation)    // No UiAutomation connection!
    }
}
```

**Why it fails:** Creating an empty `Instrumentation()` doesn't establish the critical connection to `UiAutomation`. When you try to call `dumpWindowHierarchy()` or other UIAutomator methods, you get:

```
java.lang.IllegalStateException: UiAutomation not connected!
```

### The Solution: Instrumentation Tests

Android's test framework (`am instrument`) provides a properly initialized `Instrumentation` with full `UiAutomation` access. This is how professional tools like Maestro and Appium work.

```kotlin
// This WORKS!
class UiAutomatorBridgeInstrumented(private val uiDevice: UiDevice) {
    // UiDevice was created with valid Instrumentation from test framework
}

// In test:
@Test
fun runAutomationServer() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val uiDevice = UiDevice.getInstance(instrumentation)  // Full UiAutomation access!
    val bridge = UiAutomatorBridgeInstrumented(uiDevice)
}
```

### The Clean Solution: Delete Non-Working Code

Initially, we considered keeping the non-working code with `@Deprecated` annotations. However, this creates confusion and maintenance burden. The clean solution is to **delete code that doesn't work**:

**Deleted files:**
- `UiAutomatorBridge.kt` - Created empty Instrumentation, didn't work
- `JsonRpcServer.kt` - Used non-working UiAutomatorBridge
- `AutomationService.kt` - Foreground service using non-working server
- `BootReceiver.kt` - Boot receiver for non-working service

**Kept files:**
- `BaseUiAutomatorBridge.kt` - Abstract base class with all UIAutomator logic
- `UiAutomatorBridgeInstrumented.kt` - Working implementation using proper Instrumentation
- `JsonRpcServerInstrumented.kt` - Working JSON-RPC server for instrumentation context

**Key lesson:** Don't keep non-working code "for reference" or "in case a workaround is found." It creates confusion and technical debt. Delete it and document the decision instead.

---

## Extracting Shared Code with Template Method Pattern

### The Pattern

The [Template Method Pattern](https://refactoring.guru/design-patterns/template-method) defines the skeleton of an algorithm in a base class, deferring specific steps to subclasses. This is useful when you have common logic that varies only in how certain resources are obtained.

### Our Implementation

```kotlin
// BaseUiAutomatorBridge.kt - Contains ALL the UIAutomator logic
abstract class BaseUiAutomatorBridge {

    // Abstract method - subclasses provide the UiDevice
    protected abstract fun getUiDevice(): UiDevice

    // Concrete methods use getUiDevice()
    fun dumpHierarchy(): UiHierarchyResult {
        return try {
            val outputStream = ByteArrayOutputStream()
            getUiDevice().dumpWindowHierarchy(outputStream)
            val xmlHierarchy = outputStream.toString("UTF-8")
            UiHierarchyResult(success = true, hierarchy = xmlHierarchy)
        } catch (e: Exception) {
            UiHierarchyResult(success = false, error = e.message)
        }
    }

    fun click(x: Int, y: Int): OperationResult { /* ... */ }
    fun pressBack(): OperationResult { /* ... */ }
    fun findElement(...): ElementResult { /* ... */ }
    // ... all other operations
}

// UiAutomatorBridgeInstrumented.kt - Minimal subclass, just provides UiDevice
class UiAutomatorBridgeInstrumented(private val device: UiDevice) : BaseUiAutomatorBridge() {
    override fun getUiDevice(): UiDevice = device
}
```

### Benefits

1. **DRY (Don't Repeat Yourself)**: Logic exists in one place
2. **Easier Maintenance**: Fix a bug once, it's fixed everywhere
3. **Consistency**: Both implementations behave identically
4. **Documentation**: Base class documents expected behavior
5. **Testability**: Can test base class logic with mock UiDevice

### When to Use This Pattern

Use abstract base classes when you have:
- Multiple classes with similar logic
- A common "algorithm skeleton" with varying implementation details
- The need for consistent behavior across variants

---

## Android Instrumentation Pattern

### What is Instrumentation?

Android Instrumentation is a framework for running test code that can:
- Monitor application lifecycle
- Access internal APIs and services
- Control application under test
- Get privileged access to system services (like UiAutomation)

### How Our Automation Server Uses It

```
┌─────────────────────────────────────────────────────────────────┐
│                        HOST MACHINE                             │
│  ┌──────────────────────┐         ┌─────────────────────────┐  │
│  │     MCP Server       │         │     Claude / LLM        │  │
│  │   (ToolFactory.kt)   │◄───────►│                         │  │
│  └──────────────────────┘         └─────────────────────────┘  │
│            │                                                    │
│            │ ADB commands                                       │
│            ▼                                                    │
│  ┌──────────────────────┐                                      │
│  │   adb forward        │  Port forwarding                     │
│  │   tcp:9008 tcp:9008  │  localhost:9008 → device:9008        │
│  └──────────────────────┘                                      │
│            │                                                    │
└────────────┼────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      ANDROID DEVICE                             │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              Instrumentation Process                      │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │           AutomationServerTest                     │  │  │
│  │  │  ┌──────────────────────────────────────────────┐  │  │  │
│  │  │  │         JsonRpcServerInstrumented            │  │  │  │
│  │  │  │              (Ktor HTTP)                     │  │  │  │
│  │  │  │                 :9008                        │  │  │  │
│  │  │  └──────────────────────────────────────────────┘  │  │  │
│  │  │                      │                             │  │  │
│  │  │                      ▼                             │  │  │
│  │  │  ┌──────────────────────────────────────────────┐  │  │  │
│  │  │  │      UiAutomatorBridgeInstrumented           │  │  │  │
│  │  │  │         (Uses valid Instrumentation)         │  │  │  │
│  │  │  └──────────────────────────────────────────────┘  │  │  │
│  │  │                      │                             │  │  │
│  │  │                      ▼                             │  │  │
│  │  │  ┌──────────────────────────────────────────────┐  │  │  │
│  │  │  │            UiAutomation Service              │  │  │  │
│  │  │  │      (System service for UI inspection)      │  │  │  │
│  │  │  └──────────────────────────────────────────────┘  │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                    Target App                             │  │
│  │           (App being tested/automated)                    │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### Starting the Instrumentation Server

```bash
adb shell am instrument -w \
  -e port 9008 \
  -e class com.example.automationserver.AutomationServerTest#runAutomationServer \
  com.example.automationserver.test/com.example.automationserver.AutomationInstrumentationRunner
```

Breaking this down:
- `am instrument` - Start instrumentation
- `-w` - Wait for instrumentation to finish
- `-e port 9008` - Pass "port" argument to test
- `-e class ...#runAutomationServer` - Run specific test method
- `com.example...test/...Runner` - Test package / runner class

### Why Not Just a Regular Service?

| Approach | UIAutomator Access | Security | Complexity |
|----------|-------------------|----------|------------|
| Exported Service | ❌ No UiAutomation | ❌ Security risk | Low |
| Non-exported Service | ❌ No UiAutomation | ✅ Secure | Low |
| **Instrumentation** | ✅ Full access | ✅ Secure (ADB only) | Medium |

---

## Security Best Practices

### 1. Command Injection Prevention

When executing shell commands, always validate inputs:

```kotlin
// Bad - Command injection possible
fun executeCommand(input: String) {
    Runtime.getRuntime().exec("adb shell $input")  // User could inject "; rm -rf /"
}

// Good - Use allowlist and validation
private val ALLOWED_COMMANDS = setOf("install", "forward", "shell")
private val DANGEROUS_CHARS = Regex("[;|&$()<>`\\\\\"'\\n\\r]")

fun executeAdb(vararg args: String) {
    require(args[0] in ALLOWED_COMMANDS) { "Command not allowed" }
    require(!DANGEROUS_CHARS.containsMatchIn(args.joinToString(""))) {
        "Arguments contain dangerous characters"
    }
    ProcessBuilder(listOf("adb") + args).start()  // ProcessBuilder handles escaping
}
```

### 2. JSON Injection Prevention

When building JSON manually, use a library:

```kotlin
// Bad - JSON injection possible
fun valueToJson(value: Any): String {
    return when (value) {
        is String -> "\"$value\""  // What if value contains " or \ ?
        else -> value.toString()
    }
}

// Good - Use Gson (or similar)
private val gson = Gson()
fun valueToJson(value: Any): String = gson.toJson(value)
```

### 3. Resource Management

Always close resources properly:

```kotlin
// Bad - Resource leak if exception occurs
val errorStream = connection.errorStream?.bufferedReader()?.readText()

// Good - Use Kotlin's `use` extension
val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
```

---

## Code Quality Patterns

### 1. Centralized Constants

Avoid magic numbers scattered across the codebase:

```kotlin
// Bad - Magic numbers everywhere
class AutomationClient(port: Int = 9008)  // What is 9008?
androidDevice.executeAdb("forward", "tcp:9008", "tcp:9008")
"Server running on port 9008"

// Good - Centralized constants
object AutomationConfig {
    const val DEFAULT_PORT = 9008
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535
    const val DEFAULT_HOST = "localhost"
    const val AUTOMATION_SERVER_PACKAGE = "com.example.automationserver"
}

class AutomationClient(port: Int = AutomationConfig.DEFAULT_PORT)
```

### 2. Companion Object for Stateless Utilities

Thread-safe, stateless utilities belong in companion objects:

```kotlin
class AutomationClient {
    companion object {
        private const val TIMEOUT_MS = 30000
        // Gson is thread-safe, share single instance
        private val gson = Gson()
    }

    private fun valueToJson(value: Any): String = gson.toJson(value)
}
```

### 3. Delete Non-Working Code Instead of Deprecating

When code doesn't work, delete it instead of deprecating:

```kotlin
// Bad - Keeping broken code with deprecation
@Deprecated("Doesn't work, use X instead")
class BrokenClass { /* ... */ }

// Good - Delete the broken code entirely
// Document the decision in LEARNING.md or architecture docs
// The working alternative is the only implementation
```

**Why delete instead of deprecate?**
- Deprecated code still needs maintenance
- It confuses new developers
- It clutters the codebase
- "Maybe it'll be useful later" is rarely true

---

## Common Pitfalls

### 1. Blocking Coroutines with Thread.sleep()

```kotlin
// Bad - Blocks the thread
repeat(10) { attempt ->
    Thread.sleep(1000)  // Blocks thread, wastes resources
    if (serverRunning()) return
}

// Good - Use coroutine delay
repeat(10) { attempt ->
    delay(1000)  // Suspends, doesn't block thread
    if (serverRunning()) return
}
```

### 2. Forgetting Input Validation for MCP Tools

```kotlin
// Bad - No input schema, tool might get called incorrectly
server.addTool(
    name = "find_element",
    description = "Finds element by text, resourceId, etc.",
    inputSchema = Tool.Input()  // Empty schema!
)

// Good - Declare expected parameters
server.addTool(
    name = "find_element",
    inputSchema = Tool.Input(
        properties = mapOf(
            "text" to mapOf("type" to "string", "description" to "Exact text match"),
            "resourceId" to mapOf("type" to "string", "description" to "Resource ID")
        )
    )
)
```

### 3. Not Handling Emulator Timing Issues

Emulators can be slow to initialize UIAutomation:

```kotlin
// Bad - Fails immediately on slow emulators
val uiDevice = UiDevice.getInstance(instrumentation)

// Good - Retry with backoff
private fun waitForUiDevice(instrumentation: Instrumentation): UiDevice {
    repeat(MAX_RETRIES) { attempt ->
        try {
            val device = UiDevice.getInstance(instrumentation)
            device.displayWidth  // Verify it's working
            return device
        } catch (e: Exception) {
            Thread.sleep(RETRY_DELAY_MS)
        }
    }
    throw IllegalStateException("Failed to get UiDevice after $MAX_RETRIES attempts")
}
```

---

## Further Reading

- [Android UIAutomator Documentation](https://developer.android.com/training/testing/other-components/ui-automator)
- [Maestro - Mobile UI Testing](https://maestro.mobile.dev/) - Uses similar instrumentation pattern
- [Model Context Protocol (MCP)](https://modelcontextprotocol.io/)
- [Template Method Pattern](https://refactoring.guru/design-patterns/template-method)
- [OWASP Mobile Security Guide](https://owasp.org/www-project-mobile-app-security/)
