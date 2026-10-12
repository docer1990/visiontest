# Agent Efficiency Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use agentico:subagent-driven-development or agentico:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver #57D, repeatable complete-flow evaluation with honest agent resource accounting.

**Architecture:** A versioned external adapter emits normalized events while an
independent verifier judges the fixture's final state. Measured usage, estimates,
and unavailable metrics remain separate. Reuse the cases and sample reporting from
[57C](2026-10-05-performance-command-benchmarks.md) and the [design](../specs/2026-10-05-performance-baseline-design.md).

**Tech Stack:** Python standard library and unittest; optional user-installed Codex CLI.

**First adapter proposal:** Codex CLI is used for the concrete plan because it is available
locally (`codex-cli 0.160.0` was inspected on 2026-10-05). The user has not selected
a client yet. This is replaceable and does not require Codex in VisionTest itself.
Client selection does not block tracing or command benchmarks. No agent run,
credential access, provider charge, or model download occurred during planning.

---

## Adapter protocol

Invoke an adapter with an argv array and one versioned case JSON object on stdin.
Consume JSONL on stdout; diagnostics stay on stderr. The runner owns setup,
deadlines, process cleanup, and postcondition verification. Use
`python3 -m benchmarks.run agents --cases PATH --adapter-config PATH --output DIR --samples 10`.

Every event has `schema_version: 1`, `run_id`, a unique `event_id`, and `type`.
Types are `started`, `tool_call`, `inspection`, `retry`, `image_consumed`, `usage`,
`claimed_outcome`, and `finished`. Record a fixed tool name, correlation ID,
outcome, duration, or numeric counts as appropriate. Do not include arguments,
UI text, command output, image data, reasoning, credentials, or final prose.

`started` identifies agent/client version, selected model, adapter revision,
tool-loading policy, cache-accounting semantics, and supported event capabilities.
`usage` includes `source` (`measured` or `estimated`), accounting scope, input,
output, cached-input and reasoning-output token counts when available, plus
tokenizer identity/version for estimates. Unsupported fields are null with a
reason. Deduplicate by event identity before accumulating any counts.

`finished` reports adapter completion independently of the observed flow outcome.
An absent terminal event produces an incomplete run and preserves partial metrics.
An unavailable image-consumption metric is not zero. A repeated call is not proof
of a retry; count repeated inspections separately unless the adapter can observe
the retry relationship.

### Task 1: Normalize events and account for failure-inclusive cost

**Files:**
- Create: `benchmarks/agents/__init__.py`
- Create: `benchmarks/agents/protocol.py`
- Create: `benchmarks/agents/usage.py`
- Create: `benchmarks/agents/runner.py`
- Create: `benchmarks/agents/report.py`
- Create: `benchmarks/tests/test_agent_protocol.py`
- Create: `benchmarks/tests/test_agent_usage.py`
- Create: `benchmarks/tests/fake_agent.py`
- Modify: `benchmarks/run.py`
- Modify: `docs/agentico/specs/performance-benchmarks.md`

**Review:** checkpoint. Adapter and report correctness depend on these accounting rules.

- [ ] Define `validate_event(event: dict) -> dict`,
  `summarize_flows(runs: list[dict]) -> dict`, and
  `run_agent(argv: list[str], case: dict, timeout_ms: int) -> dict`.
  Start with the resource-denominator test below.

```python
import unittest
from benchmarks.agents.report import summarize_flows

class FlowReportTest(unittest.TestCase):
    def test_failed_attempt_resources_count_per_correct_completion(self):
        runs = [
            {"observed_outcome": "success", "claimed_success": True,
             "duration_ns": 10, "measured_tokens": 100},
            {"observed_outcome": "failure", "claimed_success": True,
             "duration_ns": 20, "measured_tokens": 50},
        ]
        report = summarize_flows(runs)
        self.assertEqual(1, report["correctly_completed"])
        self.assertEqual(1, report["false_positive_claims"])
        self.assertEqual(150, report["measured_tokens_total"])
        self.assertEqual(150, report["measured_tokens_per_correct_completion"])
        self.assertEqual(30, report["duration_ns_per_correct_completion"])
```

- [ ] Test no successes, incomplete usage, duplicated events, missing terminal
  event, input including cached tokens, reasoning included in output tokens,
  mixed measured/estimated data, unknown outcomes, and saved-but-unconsumed images.
  Do not report a complete resource total when any attempt has missing usage;
  publish observed partial totals and coverage with the denominator instead.
- [ ] Reject invalid event versions, nonnumeric/negative counts, forbidden fields,
  and capabilities that contradict supplied events. Preserve valid partial
  events when the adapter times out. No agent action is automatically replayed.
- [ ] Add optional external tokenizer support through a configured argv adapter
  receiving sanitized text on stdin and returning `{tokens, tokenizer, version}`.
  Keep its output in the estimated namespace with explicit text-only scope.
  Without an adapter, estimates remain unavailable. Add no tokenizer dependency
  or model download to the standard runner.
- [ ] Run `python3 -m unittest discover -s benchmarks/tests -p 'test_agent_*.py'`.
  Expect missing protocol at RED and all accounting cases passing at GREEN.
  Commit as `feat(evaluation): define agent events and complete-flow accounting`.

### Task 2: Add the first real client adapter without guessing usage

**Files:**
- Create: `benchmarks/agents/codex.py`
- Create: `benchmarks/agents/claimed-outcome.schema.json`
- Create: `benchmarks/tests/test_codex_adapter.py`
- Create: `benchmarks/tests/fixtures/codex-events.jsonl`
- Create: `benchmarks/agents/README.md`

**Review:** checkpoint. Real measurements require a verified client mapping.

- [ ] Define `normalize_codex_events(events: list[dict], run_id: str) -> list[dict]`.
  Use a sanitized, pinned event fixture. The usage fields below are documented
  in [Codex non-interactive mode](https://learn.chatgpt.com/docs/non-interactive-mode).
  Recheck the installed client's format when implementing; unsupported client
  versions fail setup instead of silently selecting another parser.

```python
import unittest
from benchmarks.agents.codex import normalize_codex_events

class CodexAdapterTest(unittest.TestCase):
    def test_usage_preserves_cached_subset(self):
        events = [
            {"type": "turn.started"},
            {"type": "turn.completed", "usage": {
                "input_tokens": 100, "cached_input_tokens": 80,
                "output_tokens": 10, "reasoning_output_tokens": 0,
            }},
        ]
        normalized = normalize_codex_events(events, "run-1")
        usage = next(event for event in normalized if event["type"] == "usage")
        self.assertEqual("measured", usage["source"])
        self.assertEqual(100, usage["input_tokens"])
        self.assertEqual(80, usage["cached_input_tokens"])
        self.assertEqual(10, usage["output_tokens"])
```

- [ ] Launch `codex exec --json --ephemeral --model MODEL --output-schema PATH -`
  with the prompt on stdin in an isolated evaluation workspace. Require an
  explicit model and client version in adapter configuration, record their
  actual identities, and refuse an unresolved model default. The uppercase
  argument names are runtime configuration values supplied as distinct argv entries.
- [ ] Configure only the intended VisionTest tool surface for each trial and
  record effective discovery, permissions, skills, and instructions. CLI and MCP
  comparisons use the same prompt/case constraints. Reuse existing user-approved
  authentication; do not read, copy, or persist credentials in benchmark artifacts.
  Do not bypass sandbox/approval controls to make an evaluation pass.
- [ ] Deduplicate started/completed item events by item identity. Extract tool
  names and completion metadata, discard arguments and output after transient
  parsing, and record the accounting boundary of usage events. Do not sum
  cumulative counters as if they were per-request increments.
- [ ] The final schema has `claimed_success: boolean` and
  `claimed_outcome: success|failure|ambiguous`. This is agent testimony, not the
  verifier result. Preserve malformed/missing final responses as unknown claims.
- [ ] Mark retries or image consumption unavailable unless the pinned event
  schema exposes them. Do not infer image consumption from a screenshot tool
  call, filesystem write, or final prose. Distinguish a successful view operation
  from documented evidence of inclusion in model input.
- [ ] Test missing usage, failed turns, duplicate item lifecycle events, malformed
  final output, unsupported client version, and privacy filtering. `--json`
  confirms event streaming; it does not guarantee every desired metric is present.
- [ ] Run `python3 -m unittest discover -s benchmarks/tests -p 'test_*.py'`.
  Expect missing normalization at RED, then PASS. Commit as
  `feat(evaluation): add versioned Codex event adapter`.

### Task 3: Add flow cases, independent verification, and comparison gates

**Files:**
- Create: `benchmarks/agents/cases/android.json`
- Create: `benchmarks/agents/cases/ios.json`
- Create: `benchmarks/agents/verify.py`
- Create: `benchmarks/tests/test_flow_verifier.py`
- Modify: `benchmarks/fixtures/android/src/main/java/com/example/visiontest/benchmark/MainActivity.kt`
- Modify: `benchmarks/fixtures/ios/BenchmarkFixture/AppDelegate.swift`
- Modify: `benchmarks/agents/runner.py`
- Modify: `benchmarks/agents/report.py`
- Modify: `benchmarks/agents/README.md`
- Modify: `docs/agentico/specs/performance-benchmarks.md`
- Modify: `docs/performance.md`
- Modify: `CONTRIBUTING.md`
- Modify: `app/src/main/resources/agent-instructions.md`
- Create: `docs/benchmarks/agent-baseline.md`
- Create: `docs/benchmarks/evaluation-policy.md`

**Review:** checkpoint. Final #57 review and input to #60 adoption decisions.

- [ ] Define `classify_flow(expected_found: bool, observed_found: bool | None,
  backend_error: bool) -> str`. Backend failure is unknown, never proof of absence.

```python
import unittest
from benchmarks.agents.verify import classify_flow

class FlowVerifierTest(unittest.TestCase):
    def test_backend_error_cannot_prove_disappearance(self):
        self.assertEqual("unknown", classify_flow(False, False, True))
        self.assertEqual("success", classify_flow(False, False, False))
        self.assertEqual("failure", classify_flow(True, False, False))
```

- [ ] Build Italian and English flow cases against the synthetic apps from 57C.
  Include navigation with an observed `done` marker, appearance/disappearance,
  absent and ambiguous targets, disabled/non-actionable controls, and an error
  screen that must not be classified as success. Add disabled-control state to
  the fixture apps in this task if it is not part of the initial app screen.
  Keep selectors for verification separate from the agent prompt.
- [ ] For every attempt, reset outside timing, run the adapter once, stop it at
  the deadline, and verify fresh native state within a separate 5-second budget.
  Preserve agent-run latency, verification latency, and their total separately.
  A timeout with a potentially pending action has an uncertain outcome; do not
  start another sample until reset is confirmed and the target is stable.
- [ ] Compute success/false-positive rates, all-attempt latency/resources,
  successful-flow distributions, tool calls, repeated inspections, observable
  retries and images, and measured versus estimated usage coverage. Separate
  agent calls from native request counts. Preserve platform/language strata.
- [ ] Run real trials for both platforms with an explicitly pinned agent/model
  and 10 attempts per case. Write `docs/benchmarks/agent-baseline.md` from actual
  results and link sanitized run artifacts. Test doubles cannot satisfy this step.
  Record unavailable client metrics as gaps, not invented zeros or billed tokens.
- [ ] After inspecting the baseline, write numerical adoption thresholds into
  `docs/benchmarks/evaluation-policy.md`, with their baseline rationale, revision,
  outcome definitions, sample policy, and calibration/held-out separation. Freeze
  that version before #64 reads held-out cases. Threshold selection happens from
  measured evidence, so this plan intentionally supplies no arbitrary percentages.
- [ ] Run all Python tests, `./gradlew :app:test`, `./gradlew :app:e2eTest`,
  `./gradlew build`, applicable native fixture smoke/build checks, and
  `git diff --check`. Update distributed guidance without repository-relative links.
- [ ] Commit as `feat(evaluation): establish agent complete-flow baseline`, then
  run final branch review. Close #57 only with all four plans' evidence present;
  carry accounting gaps and hardware limitations into every later comparison.

## Source validation

Planning checked local `codex --version` and `codex exec --help` and fetched the
[official non-interactive documentation](https://learn.chatgpt.com/docs/non-interactive-mode)
on 2026-10-05. The exact event mapping must be tested against the pinned client.
This plan does not infer model pricing, image token cost, or provider billing.
