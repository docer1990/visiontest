# Android Stop Port-Forward Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development (recommended) or agentico:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android server shutdown verify failed ADB forward removal and test the host-side cleanup command.

**Architecture:** Add `forward --list` to the existing validated ADB surface. Inject a small host-command function into `AndroidStopToolRegistrar`; production delegates to `Android.executeAdb`, while unit tests record deterministic results and failures.

**Tech Stack:** Kotlin/JVM, coroutines, kotlin.test, MockWebServer, Gradle.

---

### Task 1: Allow validated ADB forward listing

**Files:**
- Modify: `app/src/test/kotlin/com/example/visiontest/android/AndroidValidationTest.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/android/Android.kt`

- [ ] **Step 1: Write the failing validation test**

```kotlin
@Test
fun `validateForwardArgs accepts list`() {
    Android.validateForwardArgs(listOf("--list"))
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `./gradlew :app:test --tests "com.example.visiontest.android.AndroidValidationTest.validateForwardArgs accepts list"`

Expected: FAIL because `--list` is currently treated as an invalid forwarding pair.

- [ ] **Step 3: Implement the minimal validation branch**

Add an exact `--list` branch before `--remove`:

```kotlin
when (args.firstOrNull()) {
    "--list" -> require(args.size == 1) { "forward --list accepts no additional arguments" }
    "--remove" -> {
        require(args.size == 2) { "forward --remove requires one tcp:port argument" }
        require(args[1].matches(tcpPattern)) { "Invalid port format: ${args[1]}" }
    }
    else -> existingForwardPairValidation(args, tcpPattern)
}
```

Keep the existing pair validation inline if extracting it would add no clarity.

- [ ] **Step 4: Run the focused validation tests and verify GREEN**

Run: `./gradlew :app:test --tests "com.example.visiontest.android.AndroidValidationTest"`

Expected: all `AndroidValidationTest` tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/kotlin/com/example/visiontest/android/AndroidValidationTest.kt app/src/main/kotlin/com/example/visiontest/android/Android.kt
git commit -m "feat: allow listing adb port forwards"
```

### Task 2: Verify port-forward cleanup and cover ADB invocation

**Files:**
- Modify: `app/src/test/kotlin/com/example/visiontest/tools/AndroidStopToolRegistrarTest.kt`
- Modify: `app/src/main/kotlin/com/example/visiontest/tools/AndroidStopToolRegistrar.kt`

- [ ] **Step 1: Add a recording ADB executor and the failing success-path test**

```kotlin
private class RecordingAdbExecutor {
    val commands = mutableListOf<List<String>>()
    val results = ArrayDeque<Result<String>>()

    suspend fun execute(vararg args: String): String {
        commands += args.toList()
        return results.removeFirstOrNull()?.getOrThrow() ?: ""
    }
}
```

Construct the registrar with `adbExecutor::execute` and assert:

```kotlin
assertEquals(listOf(listOf("forward", "--remove", "tcp:9008")), adb.commands)
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew :app:test --tests "com.example.visiontest.tools.AndroidStopToolRegistrarTest.stopAutomationServer force-stops packages and reports success"`

Expected: compilation failure because the registrar does not yet accept an ADB executor.

- [ ] **Step 3: Inject the production-default ADB executor**

```kotlin
class AndroidStopToolRegistrar(
    private val android: DeviceConfig,
    private val automationClient: AutomationClient,
    private val executeAdb: suspend (List<String>) -> String = { args ->
        (android as? Android)?.executeAdb(*args.toTypedArray()).orEmpty()
    },
) : ToolRegistrar
```

Call it with `executeAdb(listOf("forward", "--remove", "tcp:${AutomationConfig.DEFAULT_PORT}"))`. Existing production constructors and non-device unit fakes remain source-compatible.

- [ ] **Step 4: Run the success-path test and verify GREEN**

Run the same focused command from Step 2. Expected: PASS and the exact removal command is recorded.

- [ ] **Step 5: Add failing tests for idempotency and real failures**

Add separate tests where removal throws `CommandExecutionException` and listing returns:

```kotlin
"emulator-5554 tcp:9010 tcp:9010\n" // desired port absent: success
"emulator-5554 tcp:9008 tcp:9008\n" // desired port present: original error propagates
```

Add a third test where listing also throws and assert the original removal exception propagates. Each test must assert the command order: remove, then list.

- [ ] **Step 6: Run the new tests and verify RED**

Run: `./gradlew :app:test --tests "com.example.visiontest.tools.AndroidStopToolRegistrarTest"`

Expected: the absent-forward case fails because removal errors are not yet verified; the present and unverifiable cases expose swallowed errors.

- [ ] **Step 7: Implement verified idempotency**

```kotlin
private suspend fun removePortForward() {
    val endpoint = "tcp:${AutomationConfig.DEFAULT_PORT}"
    try {
        executeAdb(listOf("forward", "--remove", endpoint))
    } catch (removalError: CommandExecutionException) {
        val forwards = try {
            executeAdb(listOf("forward", "--list"))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (verificationError: Exception) {
            removalError.addSuppressed(verificationError)
            throw removalError
        }
        if (forwards.lineSequence().any { line -> line.split(Regex("\\s+")).getOrNull(1) == endpoint }) {
            throw removalError
        }
    }
}
```

Use a narrowly scoped helper if detekt requires reducing complexity; do not inspect error-message text.

- [ ] **Step 8: Run registrar tests and verify GREEN**

Run: `./gradlew :app:test --tests "com.example.visiontest.tools.AndroidStopToolRegistrarTest"`

Expected: all registrar tests pass.

- [ ] **Step 9: Commit**

```bash
git add app/src/test/kotlin/com/example/visiontest/tools/AndroidStopToolRegistrarTest.kt app/src/main/kotlin/com/example/visiontest/tools/AndroidStopToolRegistrar.kt
git commit -m "fix: verify Android port-forward cleanup"
```

### Task 3: Verify the app module

**Files:**
- No code changes expected.

- [ ] **Step 1: Run the complete app test suite**

Run: `./gradlew :app:test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run static analysis and diff checks**

Run: `./gradlew :app:detekt && git diff --check`

Expected: BUILD SUCCESSFUL and no diff-check output.

- [ ] **Step 3: Inspect final branch state**

Run: `git status --short && git log -4 --oneline`

Expected: no uncommitted files from this task and commits for validation and cleanup behavior.
