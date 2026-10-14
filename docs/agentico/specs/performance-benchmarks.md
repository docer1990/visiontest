# Command benchmark runner

Status: the manifest validator and sample summary contract are implemented.
CLI/MCP execution adapters, native fixtures, trace joining, and real baseline
collection are subsequent plan tasks. An empty run directory from the current
runner scaffold is not measurement evidence.

## Manifest version 1

The local JSON manifest is configuration, not a trace. It pins `platform`
(`android` or `ios`), one nonempty string `target`, `fixtureRevision`, nonempty
`cliPrefix` and `mcpPrefix` argv arrays, environment metadata, and a nonempty
`cases` array. A target list or object is rejected so the manifest cannot silently
select among multiple targets. The environment requires entries for `build`,
`nativeArtifactVersions`, `connectionType`, `targetOS`, `targetModel`, `JVM`, and
`hostOS`; each entry has either a `value` or an `unavailableReason`.

Each case has a unique `id`, `setup` (argv arrays), `cliArgs`, `mcpTool`,
`mcpArguments`, positive integer `timeoutMs`, and an assertion object whose
`kind` is one of `device_info`, `element_found`, `action_then_element`,
`wait_then_element`, `interactive_elements`, or `screenshot_file`. Commands are
argument arrays and are never shell-expanded. Assertion inputs are structured
configuration and are checked by the execution adapter; localized output text
is not an assertion. Setup duration is kept separately from command duration.
The plan has not yet fixed required assertion inputs for the five nontrivial
assertion kinds; Task 2 must define and validate those fields before adapters use
them.

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
failures, and 2 for invalid configuration. The execution adapters are delivered
separately; the current validator-only scaffold returns 1 after writing its empty
run files.
