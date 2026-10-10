# Local host performance tracing

VisionTest can append host timing records to a local UTF-8 JSONL file. Tracing is
opt in and intended for measurement. It preserves command results, exit codes,
normally returned operation failures, and MCP JSON-RPC output. It adds no probes,
retries, or native timing headers. No trace records enter stdout.

## Record a CLI command

Place the root option before the command, help, or version flag:

```bash
visiontest --trace-performance ./traces/android.jsonl get_device_info -p android --json
visiontest --trace-performance ./traces/ios.jsonl get_device_info -p ios --json
visiontest --trace-performance ./traces/help.jsonl --help
```

A missing or blank explicit path exits 2 before device components are created.
Relative paths resolve against the process working directory and normalize.
CLI mode ignores `VISIONTEST_TRACE_PERFORMANCE`. Command arguments containing
literal `--trace-performance` text remain command data.

## Record MCP tool calls

Set the environment variable on the server process, for example:

```bash
VISIONTEST_TRACE_PERFORMANCE=./traces/mcp.jsonl \
  java -jar ~/.local/share/visiontest/visiontest.jar
```

For a managed MCP server, configure the same variable in its server environment.
The server reads it once at startup; missing or blank values disable tracing.
Starting the JAR without arguments or with `serve` selects MCP mode. Arguments
after `serve` are ignored, so use the environment rather than a CLI trace option
for that mode. Stop the server normally to allow the session to drain.

One process owns each trace file with an exclusive, nonblocking lock. Use different
paths for concurrent CLI processes or MCP servers. The sink creates missing parent
directories, appends to a regular file, and rejects a target symlink, directory,
or special file. It preserves existing bytes and permissions; it does not chmod
an existing file. Reusing a path appends another session rather than replacing it.
An existing final line without a newline receives one before new records.

A file failure, queue overflow, or incomplete shutdown can add the fixed stderr
diagnostic `VisionTest performance trace is unavailable or incomplete.`. It
contains no path or exception prose and does not change the operation result.

## Read the records

A `type: session` header carries `schemaVersion: 1`, `sessionId`,
`visionTestVersion`, `mode`, and monotonic `originNs`. Completed spans have no
`type` field. They carry `sessionId`, `invocationId`, `invocationSequence`,
`spanId`, nullable `parentSpanId`, `process: host`, nullable `platform`,
`operation`, `stage`, `startOffsetNs`, `durationNs`, `outcome`, and
`operationOutcome`. Optional `errorCategory` and `metrics` fields contain only
fixed categories and numeric measurements. Match records by IDs, not line order;
children can finish before parents and concurrent invocations can interleave.

For example, this command prints span timings in milliseconds using `jq`:

```bash
jq -r 'select(.stage != null) | [.invocationSequence, .operation, .stage, (.durationNs / 1000000), .outcome, .operationOutcome] | @tsv' ./traces/android.jsonl
```

`outcome` describes control flow (`returned`, `thrown`, `timeout`, `cancelled`).
`operationOutcome` separately describes an authoritative result (`success`,
`failure`, `unknown`). A returned span can report failure. Opaque results can
remain unknown. A normal return, absent element, English result text, or successful
child span does not establish the enclosing operation's success.

Timings use the JVM monotonic clock. Divide nanoseconds by 1,000,000 for
milliseconds. Durations are inclusive and may overlap; adding parent and child
durations double counts work. Self time requires subtracting the union of child
intervals in the same clock domain. Never subtract host and device timestamps.
The session origin begins at the JVM entry point; it does not measure shell
launcher or JVM startup before that point.

## What each stage measures

| Stage | Boundary |
| --- | --- |
| `invocation` | One CLI dispatch or MCP timed tool handler; MCP formatting and framing remain outside it |
| `cli.prepare` | Entry through parser construction, parsing, and validation, ending at first lazy component access, validated `init` dispatch, or help/version/failure gateway |
| `component.init` | CLI lazy component graph construction, after preparation |
| `operation` | Shared registrar operation used by CLI and MCP |
| `health` | Existing health check; no extra check is introduced |
| `request.prepare` | JSON serialization and UTF-8 request encoding |
| `http.exchange` | Connection setup, body writes, HTTP status handling, and body reads |
| `response.process` | UTF-8 decoding and best-effort classification, or strict polling response parsing |
| `poll` | Appearance/disappearance polling, including all attempted finds |
| `poll.wait` | Each explicit delay between polling attempts |
| `screenshot.parse` | Existing capture-envelope parsing and validation |
| `screenshot.decode` | Base64 decoding |
| `screenshot.write` | Directory and temporary-file creation, writes, replacement, and cleanup |
| `device.discovery` | Existing Android discovery/cache access or iOS list query and parsing |
| `adb` | Android server startup, ADAM list/shell calls, or validated host ADB subprocess execution |
| `simctl` | Existing iOS process creation, wait, and stream drain through ProcessExecutor |
| `process.launch` | Detached instrumentation or xcodebuild process creation |

`cli.prepare` can begin before its correlated invocation span. It includes elapsed
parsing and validation time, not parser CPU time or device execution. An early
parser failure before trace configuration is available may leave no trace. MCP
startup creates components before invocations and has no individual component
spans; its session origin covers startup. A stage absent from one invocation can
mean that boundary was never reached, rather than zero time.

`requestBytes` and `responseBytes` count actual UTF-8 HTTP body bytes, including a
received error body; they are not wire traffic, model tokens, or agent image usage.
`decodedBytes` counts decoded screenshot bytes. `pollCount` includes failed find
attempts. `waitNs` accumulates actual explicit polling delays, including interrupted
delays, and excludes requests and parsing. Unmeasured metrics remain absent.
Backend errors and malformed polling results retain their failures and never
become element absence. Screenshot failures retain existing atomic replacement
and cleanup behavior. Android discovery cache hits add no ADB query. Detached
launch timings exclude server lifetime, later delays, and health polling.

## Check completeness before comparing runs

An orderly session ends with `type: session.end`, `sessionId`, `written`,
`dropped`, `invocationsStarted`, `invocationsCompleted`, and `complete`.
`written` counts accepted, persisted nonterminal records, including the header;
the footer is excluded. The default queue holds 4096 records. Offering never waits
for capacity; overflow drops records and reports the fixed diagnostic at most
once per sink. The footer reserves a path outside the queue.

Shutdown seals tracing admission and freezes invocation counts before draining.
It waits at most 1,000 ms. An invocation still active at that cutoff makes the
session incomplete even if it later finishes. Operations starting afterward still
run normally without new traced invocations. Completeness requires a terminal
record, zero drops, matching invocation counts at the cutoff, and successful drain,
flush, and close. A footer alone cannot prove resource cleanup succeeded.

Force kills, writer errors, or drain expiry can leave missing records or no footer.
Treat such sessions as incomplete and never fabricate missing span completions.
An injected writer that ignores interruption may finish an already admitted write
after shutdown returns; its frozen result stays incomplete and cleanup can remain
pending. Production FileChannel interruption closes the channel. This bounded
shutdown limits waiting; it does not guarantee a complete trace under every failure.

## Measurement limits

This delivery measures host work only. Native dispatch/SDK timings, native timing
headers, command benchmark runs, launcher measurements, and agent usage adapters
are later #57 deliveries. Missing native data means unavailable, not zero native
work or a measured one-way network latency. Existing additional CLI health checks
are preserved for future #63 measurements.

Use controlled fixtures and separate trace files for repeated measurements. Record
the VisionTest version, platform, server bundle, fixture state, and tracing setting
outside the trace. Compare complete runs with the same conditions and retain
failed or ambiguous outcomes. No speedup or tracing-overhead baseline is claimed
by this implementation. Trace events exclude arguments, serials, app IDs, paths,
UI text, image data, response bodies, and exception prose.

See the [behavioral contract](agentico/specs/performance-tracing.md) for exact
schema and lifecycle requirements and the [contributor guide](../CONTRIBUTING.md)
for verification practices.
