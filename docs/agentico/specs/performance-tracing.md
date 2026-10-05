# Host performance tracing

Status: the correlated recorder and span schema are implemented. Activation,
file lifecycle, and production measurement boundaries are subsequent delivery
steps in [the approved baseline design](2026-10-05-performance-baseline-design.md).

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
`adb`, `simctl`, and `process.launch`. This vocabulary does not imply all boundaries
are already instrumented.

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
be summed as independent work. Native timings are unavailable in this delivery;
absence never means zero native work or one-way network latency.

## Completeness

A completed invocation span describes that invocation's observed control flow;
it does not alone establish trace completeness. Reports may call a persisted trace
complete only after a terminal session record, zero dropped events, and matched
invocation completions. The recorder does not supply file lifecycle or terminal
records yet. Force-killed or failed writers can leave incomplete traces; consumers
must not fabricate missing span completions or equate completeness with operation
success.
