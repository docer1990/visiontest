# Command Performance Benchmarks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development or agentico:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver #57C, reproducible command samples and an unchanged HTTP baseline on both platforms.

**Architecture:** A Python standard-library runner owns external timing and controls
synthetic fixture apps. CLI subprocess and persistent MCP adapters produce the
same sample schema. The report joins optional host/native spans by invocation,
without mixing clock domains. See the [design](../specs/2026-10-05-performance-baseline-design.md).

**Tech Stack:** Python 3.11+, unittest, existing JVM application, native Android Activity and iOS UIKit fixture apps.

**Depends on:** [57A](2026-10-05-performance-host-tracing.md) for trace imports and
[57B](2026-10-05-performance-native-timings.md) for a complete stage baseline.

---

## Runner interface

Provide `python3 -m benchmarks.run commands --manifest PATH --output DIR --mode
cli|mcp --samples 30 --warmup 5 --tracing on|off|paired --lifecycle warm|host-cold|native-cold`.
Require positive sample/time limits, nonnegative warmups, a fresh output directory,
and a manifest that pins the launcher command and fixture/environment identity.
The runner exits 0 only when all requested samples satisfy their assertions;
it exits 1 for failed samples/setup and 2 for invalid configuration.

The manifest has `schemaVersion: 1`, `platform`, `target`, `fixtureRevision`,
`cliPrefix` (an argv array), `mcpPrefix` (an argv array), `environment`, and `cases`.
Each case has `id`, `setup` (argv arrays), `cliArgs`, `mcpTool`, `mcpArguments`,
`timeoutMs`, and `assertion`. Assertions use a fixed kind and structured inputs:
`device_info`, `element_found`, `action_then_element`, `wait_then_element`,
`interactive_elements`, or `screenshot_file`.
Assertions for action/wait commands perform an independent structured lookup
outside the timed operation; they never parse English output. Malformed operation
responses, returned RPC errors, and unsuccessful operation flags are still failures.

Environment records include explicit `value`/`unavailableReason` pairs for build,
native artifact versions, connection type, target OS/model, JVM, and host OS.
Manifest args and fixture data are local benchmark configuration, not timing traces.

### Task 1: Validate manifests and summarize all attempts

**Files:**
- Create: `benchmarks/__init__.py`
- Create: `benchmarks/manifest.py`
- Create: `benchmarks/results.py`
- Create: `benchmarks/run.py`
- Create: `benchmarks/tests/test_manifest.py`
- Create: `benchmarks/tests/test_results.py`
- Create: `docs/agentico/specs/performance-benchmarks.md`

**Review:** checkpoint. All adapters and agent evaluation consume this schema.

- [ ] Define `load_manifest(path: Path) -> dict`,
  `summarize(samples: list[dict]) -> dict`, and `main(argv: list[str] | None = None) -> int`.
  Reject unsupported schema versions, empty argv, invalid assertions, ambiguous
  target configuration, nonpositive timeouts, and existing output directories.
- [ ] Add the summary regression test before implementation.

```python
import unittest
from benchmarks.results import summarize

class ResultsTest(unittest.TestCase):
    def test_failures_remain_in_population(self):
        samples = [
            {"status": "passed", "duration_ns": 10},
            {"status": "failed", "duration_ns": 90},
            {"status": "timeout", "duration_ns": 100},
            {"status": "setup_failed", "duration_ns": None},
        ]
        result = summarize(samples)
        self.assertEqual(4, result["attempts"])
        self.assertEqual(1, result["passed"])
        self.assertEqual(1, result["setup_failed"])
        self.assertEqual(90, result["all_timed"]["median_ns"])
        self.assertEqual(100, result["all_timed"]["p95_ns"])
        self.assertEqual(10, result["successful"]["median_ns"])
```

- [ ] Define sample fields `schema_version`, `run_id`, `sample_id`, `case_id`,
  `mode`, `lifecycle`, `tracing`, `status`, nullable `duration_ns`,
  `exit_code`, `operation_outcome`, `assertion_outcome`, `trace_complete`, and
  `measurement_gaps`. Traced samples also carry `session_id`, `invocation_id`,
  and `invocation_sequence` once attribution is validated. Status is `passed`,
  `failed`, `timeout`, or `setup_failed`.
  Warmups have an explicit flag and their own population. Keep their raw records.
- [ ] Compute median conventionally and p95 by nearest rank, `ceil(0.95*n)-1`.
  Empty populations return null percentiles. Do not impute setup duration as
  operation duration. Record setup timing separately and preserve failed setup.
- [ ] Write raw JSONL, environment JSON, and summary JSON to the fresh output
  directory. Report unsuccessful samples even when a later process fails.
- [ ] Run `python3 -m unittest discover -s benchmarks/tests -p 'test_*.py'`.
  Expect missing modules at RED, then PASS. Commit as
  `feat(benchmarks): define manifests and complete sample reporting`.

### Task 2: Execute CLI and persistent MCP samples

**Files:**
- Create: `benchmarks/cli.py`
- Create: `benchmarks/mcp.py`
- Create: `benchmarks/execution.py`
- Create: `benchmarks/assertions.py`
- Create: `benchmarks/tests/test_execution.py`
- Create: `benchmarks/tests/test_mcp.py`
- Create: `benchmarks/tests/fake_mcp.py`
- Modify: `benchmarks/run.py`

**Review:** checkpoint. Fixture comparisons need trustworthy external timing.

- [ ] Define `run_process(argv: list[str], timeout_ms: int, env: dict[str, str] | None = None) -> dict`
  with `duration_ns`, `exit_code`, `stdout`, `stderr`, and `timed_out`. Keep output
  transient unless sanitized artifact capture is explicitly requested. Write this
  subprocess test first.

```python
import sys
import unittest
from benchmarks.execution import run_process

class ExecutionTest(unittest.TestCase):
    def test_arguments_are_not_shell_expanded(self):
        value = "$(touch must-not-exist); 'quoted value'"
        result = run_process(
            [sys.executable, "-c", "import sys; print(sys.argv[1])", value],
            timeout_ms=5_000,
        )
        self.assertEqual(value + "\n", result["stdout"])
        self.assertEqual(0, result["exit_code"])
        self.assertGreaterEqual(result["duration_ns"], 0)
```

- [ ] Use `perf_counter_ns`, direct argv execution, concurrent pipe draining,
  and bounded termination/reaping of the owned process group on timeout. A timed
  out device action is uncertain and is never repeated within a sample. A later
  independent sample can start only after successful fixture reset.
- [ ] Define `McpSession(argv: list[str], timeout_ms: int, env: dict[str, str] | None = None)`
  as a context manager with `initialize() -> dict`, `list_tools() -> list[dict]`,
  and `call_tool(name: str, arguments: dict) -> dict`. Adapt the existing
  `McpStdioE2ETest` handshake and newline-delimited protocol. Verify negotiated
  protocol, drain stderr, route IDs, handle notifications, and paginate tool lists.
- [ ] Measure MCP launch/initialization/discovery separately from calls. Warm
  mode reuses one initialized process; host-cold creates one per sample and
  records startup separately. CLI always starts a fresh process. Native-cold
  performs explicitly configured native stop/start outside the timed operation.
- [ ] Parse public outputs at the adapter boundary. Check JSON-RPC `error`, MCP
  `isError`, structured operation `success:false`, and assertion results separately
  from CLI exit code. Screenshot cases use an explicit output path and validate
  a newly produced PNG signature. Do not infer image consumption from that file.
- [ ] Test malformed MCP messages, unmatched IDs, subprocess timeout, failed
  initialization, stderr saturation, stdout framing, failed assertions, and
  unchanged ordered samples. Fake-MCP tests prove warm reuse by process identity.
- [ ] Run `python3 -m unittest discover -s benchmarks/tests -p 'test_*.py'`.
  Expect missing adapters at RED and PASS without attached devices at GREEN.
  Commit as `feat(benchmarks): measure CLI and persistent MCP execution`.

### Task 3: Add controlled Android and iOS fixture apps

**Files:**
- Create: `benchmarks/fixtures/android/build.gradle.kts`
- Create: `benchmarks/fixtures/android/src/main/AndroidManifest.xml`
- Create: `benchmarks/fixtures/android/src/main/java/com/example/visiontest/benchmark/MainActivity.kt`
- Modify: `settings.gradle.kts`
- Create: `benchmarks/fixtures/ios/BenchmarkFixture.xcodeproj/project.pbxproj`
- Create: `benchmarks/fixtures/ios/BenchmarkFixture.xcodeproj/xcshareddata/xcschemes/BenchmarkFixture.xcscheme`
- Create: `benchmarks/fixtures/ios/BenchmarkFixture/AppDelegate.swift`
- Create: `benchmarks/fixtures/ios/BenchmarkFixture/Info.plist`
- Create: `benchmarks/fixtures/README.md`
- Create: `benchmarks/manifests/android.json`
- Create: `benchmarks/manifests/ios.json`
- Create: `benchmarks/fixtures.py`
- Create: `benchmarks/tests/test_fixtures.py`
- Create: `benchmarks/tests/test_fixture_smoke.py`

**Review:** checkpoint. Real measurements and agent cases depend on deterministic reset.

- [ ] Register Android fixture project `:benchmark-fixture` at
  `benchmarks/fixtures/android`, reusing the existing Android/Kotlin plugin
  versions and SDK configuration. Use platform widgets; add no UI dependency.
  Build an iOS UIKit fixture with the scheme `BenchmarkFixture`.
- [ ] Implement package/bundle ID `com.example.visiontest.benchmark`. Startup
  accepts a scenario and language (`en` or `it`) and resets all state. Scenarios
  are `navigation`, `appear`, `disappear`, `missing`, `ambiguous`, and `error`.
  Expose stable accessibility/resource identifiers `start`, `email`, `continue`,
  `loading`, `done`, and `error`. The ambiguous case has two separately identified
  controls with the same visible label. The error case never exposes `done`.
- [ ] `continue` transitions navigation to `done`; timed appearance/disappearance
  transitions occur after a fixed 750 ms fixture timer. Assert the resulting
  state after the measured command, with a separate verification budget. Do not
  assume a particular number of polls or benchmark the fixture timer as CPU work.
- [ ] Define `validate_target(platform: str, selected: str, available: list[str]) -> None`
  and reject more than one active target because current VisionTest selects the
  first available device. This runner guard does not implement issue #42.

```python
import unittest
from benchmarks.fixtures import validate_target

class FixtureTargetTest(unittest.TestCase):
    def test_ambiguous_target_prevents_mutating_setup(self):
        with self.assertRaises(ValueError):
            validate_target("android", "emulator-5554", ["emulator-5554", "emulator-5556"])
        validate_target("ios", "isolated-simulator", ["isolated-simulator"])
```

- [ ] Require `target` and installed fixture identity, resolve only documented
  manifest tokens as complete argv values, and never use shell substitution.
  Setup uses existing adb/simctl install, terminate, and launch capabilities.
  Explicitly allow only documented benign setup exit codes; other failures
  produce `setup_failed` and skip the operation. Do not benchmark a user's app.
- [ ] Fill both manifests with information, lookup, interaction, appearance,
  disappearance, inspection, and screenshot cases. Use current command schemas
  and MCP names, including iOS bundle scope. No dependence on #41, #42, #53, or #56.
- [ ] Write opt-in device smoke tests in `test_fixture_smoke.py`, enabled by
  `VISIONTEST_BENCHMARK_SMOKE=1` and a manifest path in `VISIONTEST_BENCHMARK_MANIFEST`.
  Assert reset idempotence, done/error exclusivity, delayed transitions, missing
  targets, and duplicated labels through existing VisionTest inspection APIs.
- [ ] Run Python unit tests, `./gradlew :benchmark-fixture:assembleDebug`, and
  `xcodebuild build -project benchmarks/fixtures/ios/BenchmarkFixture.xcodeproj -scheme BenchmarkFixture -destination 'platform=iOS Simulator,name=iPhone 17'`.
  Install on isolated targets and run the opt-in smoke tests for each manifest.
  Commit as `feat(benchmarks): add deterministic native fixture apps`.

### Task 4: Join spans, measure overhead, and publish baseline evidence

**Files:**
- Create: `benchmarks/traces.py`
- Create: `benchmarks/report.py`
- Create: `benchmarks/tests/test_traces.py`
- Create: `benchmarks/tests/test_report.py`
- Modify: `benchmarks/run.py`
- Modify: `benchmarks/results.py`
- Modify: `docs/performance.md`
- Modify: `docs/agentico/specs/performance-benchmarks.md`
- Modify: `README.md`
- Modify: `CONTRIBUTING.md`
- Create during collection: `benchmarks/results/baseline-<run-id>/environment.json`
- Create during collection: `benchmarks/results/baseline-<run-id>/samples.jsonl`
- Create during collection: `benchmarks/results/baseline-<run-id>/summary.json`
- Create: `docs/benchmarks/http-baseline.md`

The `<run-id>` is generated by the runner for each real collection, not hand-filled
sample data. Commit sanitized baseline evidence and its provenance together.

**Review:** checkpoint. Final command benchmark delivery and dependency for 57D.

- [ ] Implement `union_duration(intervals: list[tuple[int, int]]) -> int` for
  same-clock intervals. Verify that nesting does not inflate exclusive timing.

```python
import unittest
from benchmarks.traces import union_duration

class TraceIntervalsTest(unittest.TestCase):
    def test_overlapping_children_are_not_added_twice(self):
        self.assertEqual(9, union_duration([(1, 7), (5, 10)]))
        self.assertEqual(0, union_duration([]))
```

- [ ] Give each traced CLI sample its own file. For a persistent MCP session,
  send only one operation at a time and map dispatched calls to the recorder's
  invocation sequences after session finalization. Require exactly one invocation
  per call; gaps or unexpected invocations invalidate attribution. Do not match
  arbitrary host/device timestamps or change public results to expose trace IDs.
- [ ] Join validated spans to samples by invocation and session IDs. Ignore unrelated
  startup/setup spans in operation totals. Report missing native metadata,
  incomplete sessions, dropped events, or unsupported stages as gaps. Cross-clock
  service differences are labeled estimates and are never self-time intervals.
- [ ] Implement paired tracing-on/off trials with alternating order and the same
  setup/fixture revision. Preserve each raw sample and report overhead for total
  process time and operation time separately. Record teardown/drain time within
  external CLI timing, including the writer's bounded drain cost.
- [ ] Execute both modes on both platforms with 5 warmups and 30 measured samples
  per selected case and configuration. Report the actual count, hardware, versions,
  and any failed setup. Record host-cold and warm populations separately; add
  native-cold only when the configured restart policy is successfully exercised.
- [ ] Write `docs/benchmarks/http-baseline.md` from the real outputs. Identify
  measured contributors and unresolved gaps without declaring a speedup or
  extrapolating from fake servers. If a platform is unavailable, leave collection
  incomplete and record the blocker rather than substitute synthetic results.
- [ ] Run Python tests, `./gradlew :app:test`, `./gradlew :app:e2eTest`,
  `./gradlew build`, the fixture build/smoke gates, and `git diff --check`.
  Commit harness/report changes as `feat(benchmarks): report HTTP baseline and tracing overhead`.
  Run final branch review. #57 remains open until agent evaluation is collected.
