---
applyTo: '**'
includeAgent: ["code-review"]
---

# Copilot Code Review Instructions

These instructions apply specifically to GitHub Copilot code reviews for the VisionTest project.

## Review Priorities

When performing a code review, prioritize issues in the following order:

### 🔴 CRITICAL (Block merge)
- **Security**: Vulnerabilities, exposed secrets, authentication/authorization issues
- **Correctness**: Logic errors, data corruption risks, race conditions
- **Breaking Changes**: API contract changes without versioning
- **Data Loss**: Risk of data loss or corruption

### 🟡 IMPORTANT (Requires discussion)
- **Code Quality**: Severe violations of SOLID principles, excessive duplication
- **Test Coverage**: Missing tests for critical paths or new functionality
- **Performance**: Obvious performance bottlenecks (N+1 queries, memory leaks)
- **Architecture**: Significant deviations from established patterns

### 🟢 SUGGESTION (Non-blocking improvements)
- **Readability**: Poor naming, complex logic that could be simplified
- **Optimization**: Performance improvements without functional impact
- **Best Practices**: Minor deviations from conventions
- **Documentation**: Missing or incomplete comments/documentation

## General Review Principles

When performing a code review, follow these principles:

1. **Be specific**: Reference exact lines, files, and provide concrete examples
2. **Provide context**: Explain WHY something is an issue and the potential impact
3. **Suggest solutions**: Show corrected code when applicable, not just what's wrong
4. **Be constructive**: Focus on improving the code, not criticizing the author
5. **Recognize good practices**: Acknowledge well-written code and smart solutions
6. **Be pragmatic**: Not every suggestion needs immediate implementation
7. **Group related comments**: Avoid multiple comments about the same topic

## Code Quality Standards

When performing a code review, check for:

### Clean Code
- Descriptive and meaningful names for variables, functions, and classes
- Single Responsibility Principle: each function/class does one thing well
- DRY (Don't Repeat Yourself): no code duplication
- Functions should be small and focused (generally < 30 lines; consider refactoring beyond 50)
- Avoid deeply nested code (prefer early returns and guard clauses over deep nesting)
- Avoid magic numbers and strings (use constants)
- Code should be self-documenting; comments only when necessary


## Project Context

VisionTest provides mobile automation through three components:
- **MCP server and CLI** (`app/`): Kotlin/JVM application exposing the same automation backend through MCP tools and CLI commands
- **Android automation server** (`automation-server/`): Android app providing UIAutomator access via JSON-RPC using the instrumentation pattern
- **iOS automation server** (`ios-automation-server/`): Xcode project providing XCUITest access via JSON-RPC

## Code Review Focus Areas

### 1. Security

- **Command Injection**: Verify all shell command arguments are validated against allowlists
- **JSON Injection**: Ensure Gson or similar libraries are used for JSON serialization (no manual string building)
- **Resource Leaks**: Check that streams and connections use `.use {}` or are properly closed
- **Sensitive Data**: Flag any hardcoded credentials, API keys, or secrets

### 2. Kotlin Best Practices

- Prefer `val` over `var` where possible
- Use `?.let {}` and `?:` for null safety instead of explicit null checks
- Avoid blocking calls in coroutines (`Thread.sleep()` should be `delay()`)
- If blocking I/O is unavoidable, use `withContext(Dispatchers.IO)` to prevent blocking the main dispatcher
- Use `runCatching` or proper try-catch with specific exception types
- Verify suspend functions are called from coroutine context

### 3. MCP Server Patterns

- Tools must have proper `inputSchema` with parameter descriptions
- Tool handlers should return appropriate error messages, not stack traces
- Verify timeouts are applied to device operations
- Check that `AutomationConfig` constants are used instead of magic numbers

### 4. Android/UIAutomator Specifics

- UIAutomator operations require valid `Instrumentation` - flag any direct `Instrumentation()` instantiation
- Verify `AccessibilityNodeInfo` objects are recycled after use
- Check that `UiDevice.waitForIdle()` is called before UI operations
- Ensure `compressedLayoutHierarchy` is set to false for Flutter support

### 5. Template Method Pattern

When reviewing `BaseUiAutomatorBridge` or its subclasses:
- All UIAutomator logic should be in `BaseUiAutomatorBridge`
- Subclasses should only implement `getUiDevice()`, `getUiAutomation()`, `getDisplayRect()`
- No duplicate logic between base class and implementations

### 6. Error Handling

- Verify custom exceptions from `Exceptions.kt` are used appropriately
- Check that error messages are descriptive and actionable
- Ensure retry logic uses exponential backoff for flaky operations
- Flag empty catch blocks or generic `catch (e: Exception)` without proper handling

### 7. Code Quality

- Flag TODO comments without associated issues
- Check for unused imports and dead code
- Verify public APIs have KDoc documentation
- Ensure test coverage for new functionality

## Review Checklist

When reviewing PRs, verify:

- [ ] No security vulnerabilities (injection, leaks, hardcoded secrets)
- [ ] Proper null safety and error handling
- [ ] Coroutines used correctly (no blocking calls)
- [ ] Constants from config files used (no magic numbers)
- [ ] UIAutomator resources properly managed
- [ ] Consistent with existing patterns in the codebase
- [ ] Tests included for new functionality
