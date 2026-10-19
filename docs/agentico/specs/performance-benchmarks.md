# Command benchmark runner

Status: manifests, native fixtures, CLI/persistent-MCP execution, finalized trace
joining, grouped reports, and paired tracing trials are implemented. Real baseline
collection remains incomplete; see [collection status and commands](../../benchmarks/http-baseline.md).
An empty run directory or synthetic-server test is not measurement evidence.

## Manifest version 1

The local JSON manifest is configuration, not a trace. It pins `platform`
(`android` or `ios`), one nonempty string `target`, `fixtureRevision`, nonempty
`cliPrefix` and `mcpPrefix` argv arrays, environment metadata, and a nonempty
`cases` array. A target list or object is rejected so the manifest cannot silently
select among multiple targets. The environment requires entries for `build`,
`nativeArtifactVersions`, `connectionType`, `targetOS`, `targetModel`, `JVM`, and
`hostOS`; each entry has either a `value` or an `unavailableReason`.
Optional `processEnvironment` maps explicit child-process environment variable
names to string values; those values are launch configuration, not environment
metadata or timing trace content.

Each case has a unique `id` that is a safe individual path component (no
absolute paths, slash or backslash separators, NUL, `.`, or `..`), `setup` (argv arrays), `cliArgs`, `mcpTool`,
`mcpArguments`, positive integer `timeoutMs`, and an assertion object whose
`kind` is one of `device_info`, `element_found`, `action_then_element`,
`wait_then_element`, `interactive_elements`, or `screenshot_file`. Commands are
argument arrays and are never shell-expanded. Assertion inputs are structured
configuration and are checked by the execution adapter; localized output text
is not an assertion. Setup duration is kept separately from command duration.

Assertion inputs are fixed and validated by the manifest loader:

| Kind | Required fields | Check |
| --- | --- | --- |
| `device_info` | `platform` (`android` or `ios`) | Structured device result has `success: true` and platform-specific device metadata. |
| `element_found` | `resourceId`, `expectedFound` | Structured `found` equals the expectation; a found element has the requested resource ID. |
| `action_then_element`, `wait_then_element` | `resourceId`, `expectedFound`, `lookupTool`, `lookupArguments`, `verificationCliArgs` | Run the timed command once, then do an independent structured lookup outside the timed interval and compare it. `action_then_element` requires nonempty fixture-reset setup. |
| `interactive_elements` | nonempty `requiredResourceIds` | Structured result succeeds and includes every listed resource ID. |
| `screenshot_file` | none | The adapter supplies an explicit per-attempt output path, removes any prior file, and requires a fresh PNG signature. This proves file production only, not image consumption. |

CLI runs always launch a fresh process. MCP initialization negotiates the pinned
protocol, sends `notifications/initialized`, drains stderr, matches response
IDs, skips server notifications while awaiting replies, and follows every
`tools/list` cursor. Launch, initialization, and discovery durations are stored
separately from `tools/call`. Warm MCP samples reuse the same initialized
process; host-cold samples launch one process per attempt. Native-cold requires
explicit `nativeLifecycle.stop` and `nativeLifecycle.start` argv arrays, which
run outside the measured operation. A timed-out operation is recorded as
uncertain and is never retried within its sample; each later sample runs fixture
setup first and does not start if setup fails.

After launch, setup proves fixture readiness using structured CLI `find_element`
probes with performance tracing disabled. The synthetic fixtures expose the stable
`fixture_ready` label only after the selected scenario is rendered. Appearance
adds this marker after adding `done`; disappearance adds it after removing
`loading`, in the same 750 ms completion callback. Other scenarios add it after
their initial controls. Setup polls only this positive marker until present,
avoiding snapshots of controls while their delayed transition runs. It then
requires `start` to be present before checking the selected scenario:
navigation requires `email` and the continue
control; appearance requires `done`; disappearance requires `loading` absent;
missing requires `email` absent; ambiguous requires both distinct continue IDs;
error requires `error`. Platform resource IDs and iOS bundle scope apply to every
probe. The readiness phase has one total `timeoutMs` budget, passes the remaining
budget to each probe, and waits at most 100 ms between unsuccessful observations.
A valid unexpected state may be observed again; a failed/timed-out process,
malformed output, operation error, or invalid selector result fails setup
immediately. Absence is accepted only from a boolean `found: false` result after
the positive start observation. Failure or deadline expiry records `setup_failed`
and skips the measured operation. Probe time and the 750 ms fixture transitions
belong to setup duration; settling never replays the timed command.
The marker requires the `fixture-v2-settled` fixture revision or later. Rebuild
both native fixtures and update local manifest revisions before collecting;
older fixtures lack the marker and fail readiness rather than supplying samples.

The CLI adapter evaluates public JSON output where available. The MCP adapter
parses structured content or the server's JSON text content and evaluates
`isError` and operation `success` separately from transport status. Output
streams remain transient; samples retain outcomes and failure details, not raw
command output. Action, wait, and screenshot calls require usable nonempty public
content before independent verification or file checks; an empty or malformed
result cannot pass merely because the expected element or PNG already exists.
Nonempty public text is accepted without interpreting localized wording, while
MCP error flags and structured operation failures still reject the operation.
Native JSON-RPC envelopes in CLI JSON output or inside MCP content are unwrapped once before assertion
evaluation. They require version `2.0`, an ID, a nonempty object result and no
error field. Malformed envelopes and nested native operation failures reject the
sample before verification. Ordinary payload fields named `result` are preserved.

## Samples and summaries

Raw sample records use the following fields:

```json
{
  "schema_version": 1,
  "run_id": "run-identifier",
  "sample_id": "sample-identifier",
  "case_id": "device-info",
  "mode": "cli",
  "lifecycle": "warm",
  "tracing": "off",
  "warmup": false,
  "status": "passed",
  "duration_ns": 123,
  "setup_duration_ns": 456,
  "exit_code": 0,
  "operation_outcome": "success",
  "assertion_outcome": "passed",
  "trace_complete": null,
  "measurement_gaps": []
}
```

`status` is `passed`, `failed`, `timeout`, or `setup_failed`. A setup failure has
`duration_ns: null`; any `setup_duration_ns` remains a separate measurement and
is never substituted for operation latency. A validated traced invocation may
add `session_id`, `invocation_id`, and `invocation_sequence`. These fields are
omitted until attribution has been validated. Warmups are explicitly marked and
retained in raw JSONL while receiving a separate summary population.

The runner writes `samples.jsonl`, `environment.json`, and `summary.json` into a
new output directory. Screenshot attempts use
`artifacts/<sample_sequence>/capture.png`, where `sample_sequence` is the generated
run-wide numeric attempt sequence. Their samples retain `screenshot_path` relative
to the run directory, including failed or timed-out calls that return an output
path; the reference does not guarantee the file exists or passed validation.
Separate warmups and paired tracing members never overwrite one another.
Every attempted sample, including failures and timeouts,
remains in raw output. A later process failure must not erase records already
collected. Summaries retain all attempts, count each status, and report timed
all-attempt and successful-command populations separately. Median is the
conventional median; p95 uses nearest rank `ceil(0.95*n)-1`. Empty populations
have null percentiles. Setup failures count as attempts but never as timed
operations. Setup duration has its own optional summary.

The command interface is `python3 -m benchmarks.run commands --manifest PATH
--output DIR --mode cli|mcp --samples N --warmup N --tracing on|off|paired
--lifecycle warm|host-cold|native-cold`. Sample counts and case timeouts must be
positive; warmups are nonnegative. Output directories must not already exist.
Exit status is 0 only when all requested assertions pass, 1 for sample or setup
failures, and 2 for invalid configuration.


## Trace attribution and contributors

Each traced CLI attempt has its own file. A warm MCP case has one traced session
and sends calls sequentially. The adapter retains every dispatched tools/call,
including independent verification lookups. After orderly EOF shutdown and bounded
process drain, the runner maps that ledger to contiguous invocation sequences
starting at one. Exactly one invocation is required per call, with matching
operation names. Invocation/session IDs correlate all imported spans; no matching
uses host/device timestamps or changes public tool results.

A persisted session must have a valid versioned header, terminal session.end,
matching session IDs, correct written record count, zero drops, and matching
started/completed/observed invocation counts. Missing, extra, discontinuous, or
mismatched invocations invalidate attribution for the whole session. Samples then
retain a false trace_complete and a fixed gap category, without attributed IDs.
Valid attribution may still contain measurement gaps. A traced attempt that never
reaches a registrar operation has host_operation_missing, rather than zero time.

Attributed samples retain trace_file and trace_dispatches for review, session_id,
invocation_id, invocation_sequence, host_operation_duration_ns, host_stages,
native_stages, and optional http_service_difference_estimate_ns. Stage totals have
count, inclusive_ns and self_ns. Only registrar operation spans and their
descendants contribute; preparation, component startup and unrelated invocations
are excluded. Self time subtracts the union of direct child intervals clipped to
the parent's interval, in one clock domain. Native request clocks remain separate
for each correlated HTTP exchange. HTTP minus native request duration is a signed
service-difference estimate, which may be negative, and is never labeled self time
or network latency. Missing/invalid/unsupported native metadata, missing native
stages, unsupported host/native stages, and incomplete traces remain explicit gaps.

## Paired trials and overhead

With --tracing paired, --samples N and --warmup W request N measured pairs and W
warmup pairs per case, hence N/W attempts per state. Each pair contains off/on or
on/off members, alternating the first state by pair index within each population.
Raw samples carry unique sample_id, shared pair_id, pair_order (1/2), and the
pinned fixture_revision. Setup runs separately before both members. Failed and
uncertain pairs stay in raw output and actual attempt counts; no operation is
replayed to recover results or metadata.

duration_ns retains the adapter's external timed boundary. process_duration_ns is
the full CLI process including stream drain and teardown, or the host-cold MCP
process including initialize/discovery/verification and orderly close/drain.
Warm MCP has no per-operation process lifetime and leaves that boundary null.
operation_duration_ns is the MCP tools/call round trip excluding verification.
CLI leaves this field null in both states because disabled tracing has no internal
operation timer. Traced registrar timing is a separate host_operation_duration_ns;
it cannot stand in for a missing tracing-off value.

summary.json keeps existing overall fields and adds groups by case/mode/lifecycle/
tracing, separate warmup and measured boundary populations, measured host/native
contributors, and overhead by case/mode/lifecycle. Overhead includes attempted and
successful pair counts, median signed on-minus-off deltas and median per-pair
percentages independently for operation/process boundaries. Only complete pairs
whose assertions pass and fixture revisions match enter overhead populations.
Unavailable boundaries, zero references and unsuccessful/incomplete pairs produce
gaps; empty populations have null estimates. Contributor populations are measured
attempts with available attributed spans, including failed attempts; their counts
need not equal successful command counts.

The literal {run_id} token in --output is replaced with the runner-generated UUID
before creating the fresh directory. Other text is literal. environment.json pins
the manifest SHA256 and selected case IDs alongside the supplied platform,
fixture/environment identity and requested counts. It excludes launch-only
processEnvironment values. Raw local manifests and screenshot artifacts require
review before publishing evidence. Baselines require actual device runs with five
warmups and thirty measured samples per state/configuration, preserving host-cold
and warm groups. Native-cold is eligible only after its explicit restart policy
succeeds. Hardware/version omissions and unavailable platforms keep collection
incomplete; they are not replaced with synthetic results.

## Manual CI execution

A dedicated command benchmark workflow runs only through `workflow_dispatch`.
PR, push, release and schedule events do not start it. Its optional `ref` input
accepts a branch, commit or PR head ref such as `refs/pull/123/head`; a blank
input uses the dispatched workflow ref. Resolve that input once and check out
the same immutable SHA in every measurement job.

The eight platform/mode/lifecycle configurations use separate hosted runners,
with one benchmark process per machine and paired tracing states in the same
job. CI builds and starts the current native artifacts, verifies fixture smoke,
and records actual target, source, artifact and runner identity. Native server
processes are owned and reaped on exit. A sibling failure does not cancel other
measurement jobs. CI and local latency baselines remain separate populations.

Local subprocess execution also terminates and reaps its owned process group and
closes its pipes before propagating an interruption or other exceptional exit,
including interruptions during timeout cleanup. It does not replay device work.

Each job preserves its evidence, including setup failures and partial samples,
and publishes a readable Markdown report. The final report identifies missing
configurations, unsuccessful attempts, unavailable measurements and incomplete
traces. It separates warmups and measured samples, CLI process and MCP operation
boundaries, and incompatible platforms or source SHAs. Reports include median,
p95, paired tracing overhead and available host/native contributors. A manual
run does not imply a PR-versus-main comparison or agent resource evaluation.

The workflow must reach the default branch before GitHub exposes manual dispatch.
Adding it locally neither dispatches a remote run nor completes the outstanding
command or agent baseline evidence.

The lifecycle wrapper accepts exactly three arguments, platform (`android` or
`ios`), mode (`cli` or `mcp`) and lifecycle (`warm` or `host-cold`). Invalid arity
or values exit 2 before creating evidence or contacting a backend. The manifest
pins all eight cases to 20,000 ms budgets. After building current artifacts and
generating the CI manifest, each job starts one selected native server, checks
health with bounded requests over at most 120 polls, then runs fixture smoke and
five warmup/thirty measured tracing pairs per case. Failure prevents later
phases and is never repaired by replaying startup or a measured dispatch.

Android launches only
`com.example.automationserver.AutomationServerTest#runAutomationServer` using
`com.example.automationserver.test/com.example.automationserver.AutomationInstrumentationRunner`
on port 9008. iOS launches only
`IOSAutomationServerUITests/AutomationServerUITest/testRunAutomationServer` on
port 9009 using the selected simulator UDID and current built test products.
Exit/signal traps stop and reap the owned host process, remove Android forwarding
and stop its instrumentation, or shut down the isolated iOS simulator. Cleanup
failure changes an otherwise successful run into failure while preserving an
existing nonzero collection exit code. Native-cold is excluded.

The eight jobs use separate VMs with `fail-fast: false`, one benchmark process
per host and a 120-minute job timeout. Android uses the existing API 36 x86_64
emulator action on `ubuntu-latest`. iOS uses `macos-26`, selects Xcode first and
resolves an isolated available iPhone 17 before boot. Moving runner labels do not
pin hardware. Evidence records actual runner image/runtime versions, source SHA,
fixture revision and artifact hashes where available rather than treating labels
as fixed environments.

Every job always uploads available logs, setup metadata, status, manifest,
environment, samples, summary, traces and per-attempt PNGs under a name containing
its configuration, run ID and run attempt. Setup/build failures can have no
measurement output and retain their diagnostics and resolved SHA. Per-job
Markdown reports and step summaries link to artifact downloads. The final report
checks out the resolved SHA and downloads only measurement artifacts from the
current run attempt, excluding prior attempts and combined reports. A partial
job rerun reports missing current-attempt configurations instead of merging old
evidence. All eight expected configurations and unavailable/failed jobs remain
visible; report success never overrides a failed measurement job. A failed
resolver prevents measurement and cannot supply a checkout SHA to the final
report. No PR, release, push or schedule trigger is added.
