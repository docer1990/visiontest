# Issue 60 delivery roadmap

**Goal:** Reduce latency and agent resources per correctly completed Android/iOS flow.

**Status:** The user approved the delivery direction on 2026-10-05. Host tracing
57A is implemented and reviewed on the development branch; documentation and
required gates pass, including corrections from final review. Native timing,
benchmark, and agent evaluation deliveries remain pending. This roadmap does not
authorize a production model.

**Source:** [Epic #60](https://github.com/docer1990/visiontest/issues/60), read with its
linked issues on 2026-10-05. Repository baseline is `e40165c` on `main`.

## Delivery sequence

| Delivery | Issues | Depends on | Completion evidence |
| --- | --- | --- | --- |
| Local baseline | #57 | Existing CLI/MCP | Raw samples, stage timings, overhead, real agent flow report |
| Compact inspection | #61 with #48 | Baseline for comparisons | Compatible defaults, observed selectors, explicit filtering/truncation, target-quality report |
| MCP profiles | #62 | Baseline for comparisons | Exact tool sets, compatible full default, measured discovery/usage effects |
| Health checks | #63 | Baseline request counts | Fewer requests with unchanged validation, errors, and dispatch certainty |
| Structured outputs | #48, #49, #51, #52, #53 | Their own contracts | Symmetric result schemas, existing failure semantics, CLI tests |
| CLI flows | #56 | Coordinated internal outcomes with #49/#52/#53 | Preflight of all steps, stop on failure, bounded evidence, no replay |
| Model evaluation | #64 | Baseline; reuse #61 when available | Pinned trials, held-out app/flow split, calibrated abstention, adoption decision |
| Optional engine | #65 | Passing #64 adoption gate | Persistent local worker, explicit installation, lifecycle and failure tests |
| Semantic targets/states | #66, #67 | #64 and #65 | Fresh observed evidence, ambiguity, bounded waits, complete-flow evaluation |
| Incremental inspection | #68 | Stable #61 representation | Reconstructible deltas, bounded retention, explicit full fallback |

Start model evaluation alongside early non-model optimizations once measurement
infrastructure is available. Do not run an optimization's before/after evaluation
until its unchanged baseline exists. Avoid concurrent edits to shared registrars,
CLI adapters, and output schemas; coordinate these changes at review checkpoints.

Standalone JSON for every command is not a prerequisite for #56. #51 is independent
of flow execution. #56 adds no MCP flow tool in its initial scope. #48 intentionally
changes default hierarchy presentation; other defaults change only through their
issue-specific approved contract.

## First issue, split into four plans

The [baseline design](../specs/2026-10-05-performance-baseline-design.md) and
[TD-015](../../decisions/TD-015-isolate-local-performance-measurements-from-operation-results.md)
define shared boundaries.

1. [57A, host tracing](2026-10-05-performance-host-tracing.md).
2. [57B, native timing metadata](2026-10-05-performance-native-timings.md).
3. [57C, command benchmark runner](2026-10-05-performance-command-benchmarks.md).
4. [57D, agent evaluation](2026-10-05-performance-agent-evaluation.md).

57C's pure runner work can follow 57A while native instrumentation is in progress,
but a complete baseline requires 57B. 57D consumes 57C's fixture and sample formats.
Each plan produces its own reviewed change. Keep #57 open until real Android/iOS,
CLI/MCP, and agent evidence exists. Keep #60 open until each track has a recorded
delivery or an explicit decision not to adopt it.

## What the agent adapter does

The adapter belongs only to the 57D benchmark harness. It starts a selected agent
client and translates that client's events into tool-call and usage records for
the report. A separate verifier observes whether the fixture reached the expected
state. The adapter is not part of VisionTest's CLI/MCP runtime and is unrelated to
the optional local decision engine in #65.

Different clients need different event readers. The 57D plan uses Codex as a
concrete first adapter proposal, not a required product dependency or a confirmed
user preference. Client selection does not block 57A, 57B, or 57C.

## Shared constraints

- Preserve registrar reuse, argument validation before backend access, CLI exits,
  JSON object framing, and returned-operation versus thrown-failure distinctions.
- Never replay actions after uncertain dispatch or use a successful tap as proof
  of a completed flow. Report failed and ambiguous attempts in resource totals.
- Keep local inference optional. Choose the runtime and checkpoint only after #64.
- Count measured tokens separately from named-tokenizer estimates. Record actual
  image consumption separately from screenshots captured or saved.
- Set numerical adoption thresholds from baseline data before held-out evaluation.
- Update relevant contracts and user/agent documentation with each implementation.
- Apply all repository gates without lowering coverage or refreshing baselines.

## Enabling work and exclusions

#41 may simplify fixture setup; platform setup tools are sufficient initially.
#42 supports explicit device selection; initial measurements must use one isolated
target and record ambiguity as setup failure. Neither issue blocks host tracing.
#47 and #43 remain separate workstreams. Closed foundations #38, #39, #40, #50,
and #54 are reused. No WebSocket implementation, CLI daemon, autonomous recovery,
model installation, or native polling optimization belongs to the baseline.

## Planning and execution status

- [x] Read #60 and its fifteen scoped issues.
- [x] Inspect current CLI/MCP, HTTP, native dispatch, screenshot, and test boundaries.
- [x] Obtain approval for baseline-first delivery and plain TDD.
- [x] Record the design, architecture rationale, and four implementation plans.
- [x] Execute 57A in an isolated development branch and review it.
- [ ] Execute 57B and verify installed-server compatibility.
- [ ] Execute 57C and collect command baseline and overhead results.
- [ ] Execute 57D with a real client and collect complete-flow results.
- [ ] Freeze the evaluation policy before held-out model evaluation.

57A implementation and final review are complete. #57 and #60 remain open;
57B must verify installed-server compatibility before TD-015 can be accepted.
Public product documentation accompanies the host implementation.
