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

Each case has a unique `id`, `setup` (argv arrays), `cliArgs`, `mcpTool`,
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

The CLI adapter evaluates public JSON output where available. The MCP adapter
parses structured content or the server's JSON text content and evaluates
`isError` and operation `success` separately from transport status. Output
streams remain transient; samples retain outcomes and failure details, not raw
command output.

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
new output directory. Every attempted sample, including failures and timeouts,
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
