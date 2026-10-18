# Issue 60 delivery roadmap

**Goal:** Reduce latency and agent resources per correctly completed Android/iOS flow.

**Status:** The user approved the delivery direction on 2026-10-05. Host tracing
57A was merged into `main` through PR #70 on 2026-10-12 (`c34acc8`). Native timing
57B native timing metadata is implemented and reviewed. The 57C command harness,
native fixtures, trace attribution, and paired overhead reports are implemented;
one of eight full command configurations has real baseline evidence. The rest
of the command matrix and agent evaluation remain pending. This roadmap does
not authorize a production model.

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

### Backlog audit, 2026-10-12

The audit compared GitHub's open issues and merged PRs with `main` at `c34acc8`,
before starting 57B. No open issue met all of its acceptance criteria, so none
was closed. Closed foundations #38, #39, #40, #50, and #54 remain reusable.

| Issue or delivery | Evidence and remaining work |
| --- | --- |
| #57, 57A | PR #70 merged host activation, correlation, JSONL output, and host stage instrumentation. |
| #57, 57B–57D | Native metadata, command benchmark runner, and agent flow evaluation were absent. #57 remains open until their real measurement evidence exists. |
| #48, #49, #51, #52, #53 | The scoped hierarchy, action, status, screenshot, and wait commands still lack their requested JSON output contracts. Existing inspection JSON does not complete these issues. |
| #56 | No sequential `flow` command is registered. |
| #61, #62, #63 | Compact inspection, selectable MCP profiles, and measured health-check consolidation are pending. CLI inspection still performs its preflight before the registrar. |
| #64–#68 | No model evaluation harness, local engine, semantic operations, or versioned incremental observations are implemented. Their evaluation and representation dependencies remain. |
| #41, #42, #47 | General app lifecycle operations, explicit device selection, and domain-scoped clients remain separate pending work. |
| #43 | The heap-dump ignore rule, real CLI emulator smoke workflow, and documented macOS/Linux host support exist. Namespace migration, shell completions, and Homebrew distribution are still pending. |

Baseline verification passed with 528 host tests, 162 Android unit tests, 99 Swift
tests on iPhone 17, and the host E2E/build gates. Both native servers from the
baseline accepted read-only requests with absent, valid, and invalid optional
trace headers, returning ordinary JSON-RPC results without native timing headers.
This establishes the old-server side of compatibility, not completion of 57B.

57B verification passed with 176 Android unit tests, 4 API 37 Android
instrumentation tests, 108 Swift unit tests, 3 focused iOS UI tests, 542 host
tests, 12 host E2E tests, and the complete Gradle build. Read-only live iOS and
Android requests produced correlated timing metadata; traced and untraced response
bodies matched. Existing Android and iOS server artifacts returned normal results
without timing headers, and the host recorded missing native timing without replay.

### Command benchmark implementation, 2026-10-16

The local `feat/issue-57-command-benchmarks` branch contains the runner and native
fixtures from tasks 1–3, plus task 4 trace attribution and paired reports. Its
implementation checkpoint passed with 86 Python tests (81 passed, five device
smoke tests skipped), the Gradle unit/E2E/build gate, and a clean diff check.
Both fixture apps build. The Android fixture now targets SDK 34, matching the
automation server and fixing the full-build lint failure from SDK 28.

Real fixture smoke and the Android/iOS CLI/MCP measurement matrix remain open.
Backend-unavailable traces from the actual JAR checked CLI/MCP schema compatibility
only; they are not command baseline evidence. The
[collection guide](../../benchmarks/http-baseline.md) records the pending evidence,
setup, commands, and artifacts needed to complete 57C. Do not start dependent 57D
collection or close #57 based on the harness tests alone.

### Partial collection and manual CI, 2026-10-19

Both native fixture smoke suites passed five tests, and the eight-configuration
preflight passed 128 attempts. One complete iOS CLI warm configuration passed
560 attempts (80 warmups, 480 measured). The user stopped the following iOS CLI
host-cold configuration after 49 persisted attempts. An earlier diagnostic run,
including one setup timeout, is retained separately. The
[partial baseline report](../../benchmarks/http-baseline.md) links exact manifests,
raw samples, traces, screenshots and provenance. These populations are not pooled.

The [manual CI plan](2026-10-19-manual-performance-ci.md) adds reproducible hosted
collection and readable reports. Only explicit workflow dispatch is authorized;
there are no automatic PR, push, release or schedule triggers. The local matrix
must stay stopped. CI measurements form a separate baseline from this Mac.
57C remains incomplete until the remaining configurations have real evidence;
57D and issue closure remain pending.

### Delivery checklist

- [x] Read #60 and its fifteen scoped issues.
- [x] Inspect current CLI/MCP, HTTP, native dispatch, screenshot, and test boundaries.
- [x] Obtain approval for baseline-first delivery and plain TDD.
- [x] Record the design, architecture rationale, and four implementation plans.
- [x] Execute 57A in an isolated development branch and review it.
- [x] Execute 57B and verify installed-server compatibility.
- [ ] Execute 57C and collect command baseline and overhead results.
- [ ] Execute 57D with a real client and collect complete-flow results.
- [ ] Freeze the evaluation policy before held-out model evaluation.

57A and 57B implementation and review are complete. #57 and #60 remain open
because 57C command benchmarks and 57D agent-flow evaluation still need evidence.
TD-015 is accepted for the implemented tracing and compatibility contract.
