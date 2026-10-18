# Host performance tracing

Status: the correlated recorder, local JSONL sink, CLI/MCP activation, and bounded
session lifecycle, shared HTTP transport, client polling, shared registrar operations,
Screenshot processing and device/process boundaries are implemented. Optional
native metadata validation and import are implemented on the host, and Android/iOS
servers emit bounded request-owned native timing metadata.
Command benchmarks and agent usage/outcome measurements remain later deliveries under
[the approved baseline design](2026-10-05-performance-baseline-design.md).
See the [measurement guide](../../performance.md) for activation examples and
interpretation.

## Activation and session lifecycle

CLI tracing requires a root `--trace-performance PATH` option before the subcommand
or help/version flag. MCP reads `VISIONTEST_TRACE_PERFORMANCE` once at startup;
a missing or blank environment value disables it. CLI ignores this environment
variable. No arguments and `serve` still select MCP, ignoring the `serve` tail.
Nonblank relative paths resolve against the working directory and normalize.

A first record with `type: session` contains `schemaVersion: 1`, `sessionId`,
`visionTestVersion`, `mode` (`cli` or `mcp`), and monotonic `originNs`, captured
before entry-point routing. An orderly close writes `type: session.end`, `sessionId`,
`written`, `dropped`, `invocationsStarted`, `invocationsCompleted`, and `complete`.
Written/dropped counts cover admitted nonterminal records. Completion requires a
written terminal record, successful drain, zero drops, and matching started/completed
invocations. Before stopping sink acceptance, finish atomically seals tracing admission
and freezes one coherent invocation-count snapshot. Both the footer and returned
close result use that same snapshot. A call active at this cutoff makes the session
incomplete even if it finishes during drain; late span rejection cannot turn that
session into a complete trace. Calls after the cutoff still execute normally without
starting a traced invocation. Shutdown waits at most 1,000 ms, and repeated finish
calls preserve the original result and diagnostic.

Each MCP tool handler starts exactly one invocation around its timed business
handler. The coroutine context retains correlation across timeout and IO contexts;
existing result/error formatting remains outside the invocation. Discovery and
JSON-RPC framing remain unchanged. MCP startup components precede tool invocations
and are not currently emitted as component spans; the session origin covers startup.

CLI preparation includes parser construction, parsing, and argument validation from
entry until the first lazy component access, or `init`'s explicit validated dispatch
marker. It excludes component construction and backend execution. Validation failures,
help, and version close preparation at their gateway. Preparation is a correlated
interval that can start before its runner's invocation span; it is not parser CPU
time. Parse failure before eager trace configuration is available can produce no trace.
The CLI explicitly carries its dispatch coroutine context into synchronous lazy
component construction, which emits a separate `component.init` child span.

File failures add only the fixed diagnostic
`VisionTest performance trace is unavailable or incomplete.` to stderr. They never
change normal results or exit codes. No trace data enters stdout.

## Completed host spans

Each completed span serializes as one JSON object, without JSON-RPC framing.
Fields are `sessionId`, `invocationId`, `invocationSequence`, `spanId`, nullable
`parentSpanId`, `process` (`host`), nullable `platform`, `operation`, `stage`,
`startOffsetNs`, `durationNs`, `outcome`, and `operationOutcome`. Error categories
and numeric metrics are optional. IDs are 32 lowercase hexadecimal characters;
invocation sequences increase atomically within a recorder session.

Supported platforms are `android` and `ios`; other strings become null. The fixed
operation registry covers current CLI command and MCP tool names, `help`,
`version`, and `other`. Unknown names become `other`. No argument, UI text, device
serial, app identifier, path, response body, image data, exception message, or
generic attribute map is accepted by the recorder or serialized into events.

Stages are `invocation`, `cli.prepare`, `component.init`, `operation`, `health`,
`request.prepare`, `http.exchange`, `response.process`, `poll`, `poll.wait`,
`screenshot.parse`, `screenshot.decode`, `screenshot.write`, `device.discovery`,
`adb`, `simctl`, and `process.launch`. These boundaries are instrumented where the
corresponding work occurs; absence from an invocation does not imply zero work.

## Outcomes and failures

`outcome` reports control flow: `returned`, `thrown`, `timeout`, or `cancelled`.
Coroutine timeout cancellation, JVM timeout exceptions, and socket timeouts are
`timeout`; other cancellation is `cancelled`. The exact original throwable escapes
unchanged. Nested failures complete their spans even when the caller catches them;
the enclosing span can still return normally. Sink failures cannot change an
operation return value or replace its throwable. Coroutine cancellation remains
effective if a sink cancels the active job.

`operationOutcome` is independently `success`, `failure`, or `unknown`, defaulting
to `unknown`. Only an authoritative result boundary sets it, on the active span.
A normal return, element absence (`found: false`), malformed result, or lack of an
exception does not infer success or failure. Child classification never implicitly
changes its parent or a sibling.

Fixed error categories are `validation`, `unreachable`, `device_missing`,
`unsupported_platform`, `protocol`, `transport`, `io`, and `other`. Classification
uses exception types and structured CLI exit codes, never exception prose.
Ordinary cancellations and non-socket timeout exceptions omit the error category.

## Metrics and clocks

Allowed metrics are `requestBytes`, `responseBytes`, `decodedBytes`, `pollCount`,
and `waitNs`. Repeated measurements accumulate on the active span with safe
concurrent updates. Negative samples are ignored; totals saturate at the maximum
signed 64-bit integer. Unmeasured metrics remain absent. Request and response byte
counts refer to UTF-8 bodies at their actual transport boundary, not character
counts or total network traffic. Decoded bytes refer to decoded screenshot data;
poll counts and waits belong to their measured polling boundary.

Durations and session-relative start offsets use the injected monotonic JVM clock
(`System.nanoTime()` by default). Values are nonnegative. Coroutine context carries
the invocation and immutable parent identity across suspensions and dispatcher
changes; leaving a span restores its parent. Overlapping invocations remain
isolated, and a different recorder cannot inherit another recorder's active span.
Disabled tracing and spans outside an invocation execute their blocks without
recording. There is no mutable global current invocation or plain thread-local.

Host and device clocks are separate domains. Never subtract timestamps from
different processes. Inclusive spans can overlap; self time requires the union of
child intervals within the same clock domain. Parent and child durations must not
be summed as independent work. Native timings use request-relative device offsets;
absence never means zero native work or one-way network latency.

## Shared transport and client waits

Default public client constructors disable tracing; internal constructors accept a
recorder. CLI component creation and MCP factory creation pass their session recorder
to both platform clients. HTTP tracing sends the same body and JSON-RPC ID once,
retains existing connect/read timeouts, and returns the original decoded response.
Only an active traced RPC adds the optional timing request header described below.
It adds no health probes or retries.

`request.prepare` covers JSON serialization and UTF-8 request encoding.
`http.exchange` covers connection setup, body writes, status handling, and body
reads. Its `requestBytes` counts the successfully written UTF-8 body; `responseBytes`
counts received body bytes, including an HTTP error body when present. Neither is
wire traffic. `response.process` covers UTF-8 response decoding and best-effort
structured classification. A strict boolean `result.success` sets success/failure;
an RPC error object without a result sets failure; other or malformed bodies stay
unknown and return unchanged. An iterative scan bounds object/array nesting at 64
levels before tree parsing; deeper bodies remain unknown without recursive parsing.
Braces and brackets inside JSON strings do not count toward this limit. Disabled
tracing skips this extra classification.

`health` preserves the existing boolean result and error handling, classifying true
as success and false as failure. `poll` surrounds appearance/disappearance polling.
Its `pollCount` includes every attempted find, including a failed one. A matching
presence condition sets success. Existing strict polling parsing has a separate
`response.process` span: malformed responses and backend errors still throw and
never become absence. `poll.wait` surrounds each explicit delay; the enclosing
poll's `waitNs` accumulates actual monotonic host time around these delays, including
an interrupted delay. It excludes find requests and response parsing. No explicit
wait leaves this metric absent. Poll intervals, timeout budget, and result messages
retain their existing behavior. Legacy wrapped command errors retain the fixed
`other` category without inspecting exception messages.

## Native timing protocol version 1

Only an RPC within an enabled, active host invocation sends `X-VisionTest-Trace`:
`v1;<invocationId>;<requestSpanId>`. Both IDs are 32 lowercase hexadecimal
characters. `requestSpanId` identifies that RPC's host `http.exchange` span;
it is independent of the JSON-RPC ID. Concurrent calls with identical RPC IDs
therefore remain independently correlated. Requests outside an invocation and
all `/health` calls send no timing header and produce no native timing expectation.

A supporting server returns `X-VisionTest-Timing`, base64url UTF-8 JSON (padding
is optional). Version 1 has exactly these fields:

```json
{"version":1,"invocationId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","requestSpanId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","spans":[{"spanId":"cccccccccccccccccccccccccccccccc","parentSpanId":null,"stage":"request","startOffsetNs":0,"durationNs":10,"outcome":"returned","metrics":{"pollCount":2}}],"missingStages":["ui_sync"]}
```

Each span requires `spanId`, nullable `parentSpanId`, `stage`, `startOffsetNs`,
`durationNs`, and `outcome`; `metrics` is optional. Span IDs and nonnull parent
IDs use the same lowercase hex grammar. Parents refer only to spans present in
this native snapshot. A null parent means a native root, never the host request
span. Multiple roots are permitted for partial snapshots; correlation with the
host request is provided by the envelope. Children must fit within their parent's
interval; IDs are unique and the parent graph must be acyclic.

The fixed native stage registry is `request`, `decode`, `queue_wait`, `operation`,
`ui_sync`, `poll`, `poll_wait`, `screenshot_capture`, `png_encode`, `base64_encode`,
`response_encode`, and `screenshot_capture_encode`. These are native stages, not
host `TraceStage` values. `missingStages` is a unique list from this same registry.
Combined platform APIs use the combined stage and name unavailable sub-stages.
Unavailable SDK internals and unfinished work appear as missing, never zero.
Poll attempts and elapsed waits are aggregated within the completed polling
boundary rather than creating unbounded spans. On iOS, a poll starts before the
first readiness check and ends when readiness/focus polling finishes; later actions
and response encoding do not extend it. Focus acquisition may include the tap
needed to obtain focus. Android polling wrappers can include the eventual
interaction. Aggregate `waitNs` measures disjoint explicit delays and does not
create a contiguous `poll_wait` span; that stage stays missing unless its actual
interval is measured. On iOS, freezing during polling omits the unfinished poll,
including its partial metrics, and keeps `poll` missing. Late completion cannot
change the frozen response.

Offsets are relative to the native request's monotonic origin. Offsets, durations,
and metrics are JSON integer numbers from zero through signed 64-bit maximum;
strings, fractions, booleans, negative values, overflow, and intervals whose end
overflows are invalid. Outcome values are `returned`, `thrown`, `timeout`, and
`cancelled`. Metrics accept only `requestBytes`, `responseBytes`, `decodedBytes`,
`pollCount`, and `waitNs`. Unknown fields, stages, outcomes, or metrics are rejected.
The encoded header is at most 8,192 ASCII characters and contains at most 32 spans.
Malformed UTF-8, malformed JSON, and JSON nested beyond 16 levels are rejected
before import. Both correlation IDs must match the traced request.

The host emits one `type: native.timing` record per traced RPC. It contains
`sessionId`, `invocationId`, `invocationSequence`, `requestSpanId`, `platform`,
`process` (the native platform), `operation`, `clockDomain: native.request`,
`status`, `spans`, and `missingStages`. Native offsets are never translated onto
the host clock. This separate envelope retains the host correlation while keeping
native spans out of the host stage registry. Its bounded sink admission and failure
isolation are the same as host records.

Status is `available` for accepted metadata, `missing` when no response header
exists (including connection loss before headers), `invalid` for malformed or
out-of-bounds metadata, `mismatched` for valid IDs belonging to another request,
and `unsupported` for an unknown numeric protocol version. Rejected metadata has
empty span and missing-stage lists; rejection never imports partial data. Metadata
is captured even on HTTP failures. The original response bytes, decoded body,
exception behavior, dispatch count, and timeouts remain unchanged. A missing header
from an older server needs no support probe, fallback retry, or server upgrade.
Trace sink failures cannot replace the result or throwable.

Servers freeze the normal response body before encoding the timing header. Native
request service duration excludes header encoding and socket writes. These producer
boundaries are established by each platform's native instrumentation delivery.

### Producer coverage and compatibility

The Android and iOS servers accept the optional request header independently of
the JSON-RPC method set. Older installed APK pairs and iOS bundles ignore that
header and continue serving existing commands. A traced call to an older server
produces `status: missing` with empty `spans` and `missingStages`; this means no
native snapshot was returned, not that native work was zero. VisionTest never
replays an operation to obtain timing data. Rebuild and install the Android APK
pair, or update/rebuild the iOS test bundle, to collect native spans. No upgrade
is required for ordinary commands.

Android measures request/decode/operation/response encoding, and where exposed,
UI idle waits, element polling, screenshot capture, PNG encoding, and base64
encoding. It has no separately measurable request queue boundary, and Android
UIAutomator can perform additional synchronization inside SDK calls; those
boundaries remain unavailable. Poll attempts and waits are aggregated. iOS
measures request/decode/operation/response encoding, receipt-to-main-thread queue
delay, exposed polling waits, and screenshot capture/PNG/base64 stages. Its UI
synchronization coverage is limited to explicit wrapped waits. XCTest scheduling
and SDK internals remain unavailable. iOS freezes metadata when the HTTP response
snapshot is taken; work still queued past the existing main-thread wait can be
reported missing and cannot mutate that frozen snapshot. Both producers bound
snapshots to 32 spans and the encoded header to 8,192 characters; omitted or
unavailable stages stay missing rather than being assigned zero duration.

## Shared operations and screenshots

Every shared registrar operation owns one `operation` span for both CLI dispatch
and MCP tool handlers. Delegated interaction operations use this same boundary.
The tool DSL retains only its invocation span; it does not duplicate the operation
span. Public registrar constructors continue to disable tracing. Internal factory
construction passes the session recorder to registrars and their screenshot savers.
The CLI holder configures its derived registrars before publishing the constructed
holder, preserving its original eight-argument constructor and eager construction.
Existing validation order, health checks, RPC calls, return text, and exceptions
remain unchanged.

Boolean app-launch and status results, explicit selector validation failures, and
missing server artifacts classify the operation at their existing result branches.
Opaque forwarded responses and unclassified results remain unknown; transport or
poll child classification does not implicitly classify an enclosing operation.

`screenshot.parse` covers the existing envelope parsing and validation after the
capture response returns. It reports handled malformed envelopes and backend
failures as returned failures. `screenshot.decode` covers base64 decoding and records
`decodedBytes` only for decoded data. Invalid base64 remains a returned failure.
`screenshot.write` covers parent and temporary-file creation, file writing, replacing
moves, and existing cleanup. The saver explicitly classifies the enclosing operation
from its typed parse or persistence result. Atomic-move fallback, best-effort cleanup,
handled I/O failures, and unexpected thrown exceptions retain their behavior.
Screenshot spans serialize no response, image, target path, or exception prose.
Response byte counts remain at the existing HTTP boundary rather than re-encoding
the screenshot response solely for a duplicate metric.

## Device discovery and processes

Production CLI and MCP construction passes the session recorder to Android and to
IOSManager's simulator and both its ordinary and 120,000 ms boot-wait executors.
Legacy public Kotlin and JVM constructors, including no-argument constructors,
continue to disable tracing.

`device.discovery` includes Android startup, lock acquisition, cache lookup, and
on cache misses the existing list query and mapping. A cache hit still records
this stage but makes no additional ADB list call. iOS discovery includes the
existing simctl list query and JSON parsing. Empty successful queries do not imply
that a device is available.

`adb` surrounds actual Android server startup, ADAM list and shell requests, and
validated host ADB subprocess execution. Invalid host arguments still fail before
device access or process creation. `simctl` surrounds ProcessExecutor's existing
process creation, wait, and stream draining. Its exit code sets operationOutcome
success or failure, while a nonzero code still returns CommandResult normally.
CommandTimeoutException retains its type and message and records timeout without
an error category. Existing timeout budgets, cleanup, stream threads, locking,
cache policy, output, and exception propagation remain unchanged.

`process.launch` surrounds detached instrumentation or xcodebuild process creation
only. It ends before startup delays and health polling; it does not measure the
server lifetime. Process creation failures retain their existing propagation.
No helper thread emits measurements: stream draining remains inside its enclosing
process span. Device and process traces never record serials, command arguments,
paths, stdout, stderr, or exception messages, and add no probes or retries.

## Local JSONL persistence

The sink appends UTF-8 JSON objects as newline-delimited records through one daemon
writer. Its bounded queue holds 4096 records by default. Offering a record never
waits for queue capacity: overflow rejects the record and counts it as dropped.
Offers after shutdown begins or writer failure are rejected. Writer errors and
queue overflow produce at most one fixed diagnostic per sink, without file paths
or exception messages; diagnostic callback failures do not escape.

Opening a sink creates missing parent directories and requires a regular target
file. Symbolic links, directories, and special files are rejected. The target is
opened with `NOFOLLOW_LINKS` and append semantics and held with an exclusive,
nonblocking process lock. A conflicting owner disables this sink without changing
operation results. Independent files can have independent owners. Descriptors used
to inspect the final byte remain open for the owner's lifetime, and local ownership
checks prevent a conflicting open from releasing another sink's process lock.
An existing unterminated final line receives a newline before the first new record;
existing bytes are never truncated. Existing file permissions are not changed.
Failed opening and normal shutdown release the sink's resources.

Shutdown rejects new offers, drains accepted records, flushes them, and waits for
writer closure up to the caller's wait budget. The immutable, idempotent result reports
`complete`, `written`, and `dropped`; `complete` requires successful drain, flush,
and close with zero drops. Records pending or in flight when the deadline expires
count as dropped in that result. An optional terminal-record callback runs after
the queued records have drained and flushed. Its record bypasses queue capacity,
uses the final event counts, and is excluded from those counts. The sink checks
the deadline immediately before admitting that record, including after callback
execution; an expired drain or flush therefore omits it.

The wait is bounded even for an injected `Writer` that ignores interruption. Such
a writer can finish an already admitted write after shutdown has returned, and
resource cleanup can remain pending until the writer unblocks. That late write
cannot change the returned incomplete result. The sink suppresses further record
admissions and interrupts its worker on expiry; production `FileChannel` I/O
responds to interruption by closing the channel. A terminal write already admitted
before expiry likewise cannot be retracted for an arbitrary injected writer. A
terminal record alone does not prove that shutdown or cleanup succeeded.

## Completeness

A completed invocation span describes that invocation's observed control flow;
it does not alone establish trace completeness. Reports may call a persisted trace
complete only after a terminal session record, zero dropped events, and matched
invocation completions at the sealed admission cutoff. `TraceRuntime` supplies the
file lifecycle and terminal records. Force-killed or failed writers can leave incomplete traces; consumers
must not fabricate missing span completions or equate completeness with operation
success.
