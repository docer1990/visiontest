# Manual performance benchmark CI implementation plan

> **For agentic workers:** Use agentico:subagent-driven-development to execute the tasks and review checkpoints.

**Goal:** Run command performance benchmarks manually against main, a branch, a commit, or a PR head and publish a readable report with raw evidence.

**Architecture:** A resolver pins the selected ref to one SHA. Eight isolated hosted jobs run Android/iOS, CLI/MCP, and warm/host-cold configurations. Python helpers produce honest environment manifests and Markdown reports. Existing native servers, fixtures and command runner supply the measurements.

**Tech Stack:** Existing pinned GitHub Actions, Python standard library, Bash, Gradle, adb, Xcode and simctl.

**Branch:** feat/issue-57-command-benchmarks off feat/issue-57-native-timings.

**Decisions:** TD-015. The user explicitly selected manual execution only on 2026-10-19; no PR, push, release or scheduled event starts this workflow.

## Files

- `benchmarks/ci_manifest.py` discovers isolated targets and records actual checked-out source/artifact/environment identity.
- `benchmarks/ci_report.py` renders raw run evidence without combining different platforms or timing boundaries.
- `.github/scripts/command-benchmarks.sh` owns native server lifetime, fixture smoke and one benchmark configuration.
- `.github/workflows/command-benchmarks.yaml` resolves the selected ref, defines eight jobs, uploads artifacts and renders the overall report.
- Focused Python tests cover the new helper behavior. `benchmarks/README.md`, `docs/performance.md` and the behavioral contract describe manual usage and limits.

## Task 1: Build CI manifests and select the iOS target

**Review:** checkpoint. The workflow and lifecycle script consume this interface.

Create `benchmarks/ci_manifest.py` and `benchmarks/tests/test_ci_manifest.py`.

Expose `select_ios_target(devices: dict) -> str`, `build_manifest(platform: str, target: str, environment: dict, fixture_revision: str, timeout_ms: int = 20000) -> dict`, and `main(argv: list[str] | None = None) -> int`.

The CLI has `ios-target`, which reads available simulators and prints one available iPhone 17 UDID, and `write --platform android|ios --output PATH`, which writes a fresh validated manifest. Select the newest available iOS runtime deterministically, reject ambiguity and unexpected booted simulators, and never boot or install from this helper. The workflow performs booting. Writing requires one online/booted target and existing current JAR, fixture and native server artifacts. Use the existing manifest templates and fixed fixture identity, pin the actual fixture tree revision and 20-second case deadlines, and disable inherited tracing in launch configuration.

Record actual git HEAD, JAR/fixture/native artifact SHA256 values, JVM, host OS/CPU/memory, target OS/model, transport, and selected runner image metadata. Read only named nonsecret CI environment fields. Optional unavailable metadata uses an explicit reason; missing target or required artifacts fails setup instead of creating placeholders. Never copy the full process environment into artifacts. iOS native derived data is `test-output/benchmark-native`; the CI artifact directory is separate from derived data.

Start with a failing selector regression using this exact public call:

```python
def test_latest_available_iphone_is_selected(self):
    devices = {"devices": {
        "com.apple.CoreSimulator.SimRuntime.iOS-26-2": [{"name": "iPhone 17", "udid": "older", "isAvailable": True, "state": "Shutdown"}],
        "com.apple.CoreSimulator.SimRuntime.iOS-26-5": [{"name": "iPhone 17", "udid": "newer", "isAvailable": True, "state": "Shutdown"}],
    }}
    self.assertEqual("newer", select_ios_target(devices))
```

Cover ambiguous/offline targets, missing artifacts, actual source identity rather than event SHA, template selectors and pinned timeout, safe metadata collection and invalid arguments before subprocess calls. Use fake command results and temporary artifact files; no device commands in tests. Run `python3 -B -m unittest benchmarks.tests.test_ci_manifest -q`. Commit as `feat(benchmarks): prepare reproducible CI manifests`.

## Task 2: Render readable benchmark reports

**Review:** checkpoint. Both matrix jobs and the aggregate job use the report renderer.

Create `benchmarks/ci_report.py` and `benchmarks/tests/test_ci_report.py`. Expose `render_report(input_dir: Path, expected_configurations: int = 1) -> str` and `main(argv: list[str] | None = None) -> int`. The CLI accepts `--input-dir`, `--output`, and `--expected-configurations` and writes Markdown both to the output file and stdout. It performs no device access.

Discover run directories containing `environment.json` and `samples.jsonl`. Keep each run/platform/mode/lifecycle and SHA separate. Reuse existing sample/report accounting. Show tested SHA and provenance, actual/expected counts, passed/failed/setup-failed/timeout counts, separate warmups, measured median and p95, paired tracing deltas/percentages, measured host/native contributors and explicit gaps. Explain CLI process versus MCP operation boundaries, and do not infer a cross-mode speedup or agent tokens. Include artifact file names and relative links suitable for downloaded artifacts. Escape Markdown-sensitive metadata.

Start with a failed-attempt regression: one passed 10 ns sample and one failed 90 ns sample must show two attempts and one failure, retain both in the all-timed median and avoid claiming a complete baseline. Add tests for no produced results, partial samples, missing configurations, unavailable boundaries, setup failure with null operation duration, separate warmups/platforms/SHAs and incomplete traces. No results means setup did not reach the runner, not zero-latency success. Run `python3 -B -m unittest benchmarks.tests.test_ci_report -q`. Commit as `feat(benchmarks): render readable performance reports`.

## Task 3: Add the manual workflow and native lifecycle wrapper

**Review:** checkpoint. This is the final integration checkpoint.

Create `.github/scripts/command-benchmarks.sh` and `.github/workflows/command-benchmarks.yaml`. Update `benchmarks/README.md`, `docs/performance.md`, and `docs/agentico/specs/performance-benchmarks.md`.

Only `workflow_dispatch` triggers the workflow. Its optional string input `ref` selects a branch, SHA or `refs/pull/123/head`; blank uses the dispatched workflow ref. A read-only resolver checks out the ref and outputs git HEAD once. All eight benchmark jobs check out that immutable SHA, with checkout credentials not persisted. Reuse action SHA pins from existing workflows. Android uses Ubuntu with the existing API 36 x86_64 emulator action; iOS uses macos-26, Xcode, and an isolated iPhone 17 selected before booting. Record actual image/runtime versions rather than treating moving runner labels as fixed hardware.

Build the JAR, current native server artifacts and fixture per job. The Bash wrapper accepts exactly platform, mode and lifecycle; validate all arguments before backend access. Generate the CI manifest, start only the selected current native server once, poll health with a finite deadline, run fixture smoke and then 5 warmup/30 measured paired samples for all eight cases. Use 20-second configured case limits. Own and reap the instrumentation/xcodebuild process with a trap on success, failure and interruption. No replay after uncertain startup or dispatch. Native-cold remains excluded. Run one benchmark process per hosted machine; jobs on separate runners may execute concurrently. Disable matrix fail-fast so one failed job does not erase siblings' evidence. Give each benchmark job a 120-minute limit.

Always preserve logs, manifests, environment, samples, summary, trace and per-attempt PNGs as uniquely named downloadable artifacts, including setup/build failures. Render a per-job Markdown report and add the upload action's artifact URL to its GitHub step summary. An always-running final report job downloads available evidence and renders one report for all eight expected configurations, showing missing or failed jobs honestly. It checks out the resolved SHA and does not merge incompatible timing populations. The overall workflow still fails when a benchmark job fails.

Document UI/manual CLI commands for main and PR refs, that workflow_dispatch needs this workflow on the default branch, the evidence format, full-run duration/cost implications, and separate CI versus local baselines. Show `gh workflow run command-benchmarks.yaml -f ref=main` and `gh workflow run command-benchmarks.yaml -f ref=refs/pull/123/head`. Do not dispatch, push, publish or change existing PR/release workflows as part of local implementation.

Validate Bash syntax, YAML parsing and actionlint if installed. Add focused fake-command tests for invalid arguments before backend access and cleanup after failure; never access real targets in these tests. Run focused Python tests and `git diff --check`, then the parent runs the complete Python and Gradle gates and independent final review. Commit as `ci(benchmarks): add manual performance matrix`.

## Status

- [ ] Task 1.
- [ ] Task 2.
- [ ] Task 3.

The user stopped the separate local baseline collection. One iOS CLI warm configuration completed 560 attempts, and a later configuration retained 49 attempts before interruption. CI implementation does not restart that local matrix or claim full baseline completion.
