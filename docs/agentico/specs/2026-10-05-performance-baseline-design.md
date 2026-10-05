# Local performance baseline design

## Status and scope

The user approved the delivery direction on 2026-10-05. This document makes the
interfaces concrete for [#57](https://github.com/docer1990/visiontest/issues/57),
the first delivery in [#60](https://github.com/docer1990/visiontest/issues/60).
These are planned contracts, not claims about the current release.

Deliver host tracing, native timings, command benchmarks, and agent evaluation
as four independently reviewed changes. Completing host tracing alone does not
close #57. Preserve TD-003 registrar sharing, TD-006 client polling, TD-010 native
atomic interactions, and existing CLI exit semantics.

Use plain TDD with the repository's Kotlin and Swift suites and Python unittest
for the external harness. No Gherkin runner or production dependency is required.
TD-015 records the measurement boundary. Do not optimize health checks, polling,
transport, or UI synchronization in this work.

## Activation and lifecycle

- The CLI accepts a root option before the subcommand, for example
  `visiontest --trace-performance trace.jsonl get_device_info --platform android`.
- MCP reads `VISIONTEST_TRACE_PERFORMANCE` once at startup. No arguments and
  `serve` retain their existing routing, including ignoring arguments after `serve`.
  The environment variable is MCP-only; CLI activation is explicit.
- Resolve nonblank relative paths against the process working directory. A missing
  CLI option value or a blank value is a usage error with code 2 before device
  construction. A blank MCP environment value disables tracing.
- Capture an entry timestamp before routing. Record CLI preparation from entry to
  the validated command runner, separately from lazy component initialization.
  Label this interval `cli.prepare`; it includes parser construction and validation,
  not just parser CPU time. A parse failure before trace configuration is available
  may produce no trace and is documented as a measurement gap.
- Opening or writing a trace file must not replace an operation outcome. Emit one
  fixed diagnostic to stderr, disable the failed writer, and continue the operation.
  Do not include exception messages or the configured path in this diagnostic.
- Use a single writer and a bounded queue of 4,096 events. Never block an operation
  waiting for queue capacity. Report dropped event counts and incomplete traces.
  On normal CLI exit or MCP shutdown, drain for at most 1,000 ms. Diagnostics report
  an incomplete drain even if a final file record cannot be written.
- Open a regular local file in append mode with an exclusive nonblocking process
  lock. Reject special files and symlinks. Concurrent processes must use different
  paths; a lock conflict disables tracing for that process without blocking work.
- Close spans before `exitProcess`, including mapped CLI errors, help, and version
  where activation occurred. Do not rely on a `finally` outside `exitProcess`.
  Force-killed processes produce incomplete traces, never fabricated completions.

## Trace schema version 1

Each line is one JSON object. A session record identifies schema version, a random
session ID, VisionTest version, invocation mode, and the process's monotonic origin.
No device serial, app identifier, path, host environment dump, arguments, UI text,
response body, image data, or exception message belongs in a trace.

A completed span contains `sessionId`, `invocationId`, `invocationSequence`, `spanId`, nullable
`parentSpanId`, `process` (`host`, `android`, `ios`), nullable `platform`, a fixed
`operation` name, a fixed `stage`, `startOffsetNs`, `durationNs`, and `outcome`.
Operation names come from a registry; unknown caller-supplied names become `other`.
Platform may be absent during parsing, cross-platform startup, or invalid input.

The outcome vocabulary is `returned`, `thrown`, `timeout`, and `cancelled`.
Separately, `operationOutcome` is `success`, `failure`, or `unknown` when an
authoritative structured result is available. A normal return is not necessarily
operation success. Element absence is not automatically failure. A malformed
result never becomes success. Add classification at existing result-handling
boundaries without adding exceptions or parsing prose to change behavior.

Optional numeric metrics are allowlisted: `requestBytes`, `responseBytes`,
`decodedBytes`, `pollCount`, and `waitNs`. Count UTF-8 body bytes at their actual
boundary, not characters or total on-wire traffic. Unknown byte counts are absent.
Error categories are fixed enums; raw errors cannot enter a generic attribute map.
Use `validation`, `unreachable`, `device_missing`, `unsupported_platform`,
`protocol`, `transport`, `io`, or `other`; keep absent categories absent.

Record a terminal session event with written/dropped counts on orderly shutdown.
Reports accept a trace as complete only with that event, zero drops, and matched
invocation completion. Trace completeness and operation success are separate.

## Correlation and clocks

Create an invocation ID at the CLI runner and each MCP tool entry. Propagate an
immutable parent span context through coroutines, including `Dispatchers.IO`.
No global mutable current-invocation variable or plain thread-local is permitted.
Explicitly carry context into native queues and host subprocess instrumentation.
Assign `invocationSequence` from an atomic per-session counter at invocation start.
The command runner sends one MCP operation at a time and associates samples with
these sequences after trace finalization. Require one corresponding invocation
per dispatched tool call; missing or extra records make attribution incomplete.
Agent runs with concurrent calls report session totals unless their adapter can
prove a finer mapping. Do not guess correspondence from wall-clock timestamps.

Use `System.nanoTime()` on JVM and a monotonic Swift clock. Each process measures
its own durations. Never subtract host and device timestamps. Native start offsets
are relative to that native request, not to the host origin.

Inclusive spans may overlap. Compute self time only from the union of child
intervals on the same clock domain. Never sum a parent and its descendants.
Client request duration minus correlated native service duration is an estimate
of communication plus uninstrumented overhead, not one-way network latency.

## Host boundaries

Instrument application preparation, component creation, invocation and shared
operation, health checks, JSON serialization, HTTP exchange, response decoding,
client polling, screenshot parsing/base64 decoding/file writing, device discovery,
ADB library calls, ADB subprocesses, and simctl subprocesses where applicable.
For detached server launch, measure launch only, not the daemon's whole lifetime.

Preserve the original HTTP body, JSON-RPC ID, timeout, health check count, and
exception behavior. Do not retry any operation because timing data is unavailable.
Capture failure spans in `finally` and preserve cancellation. Trace helpers must
never swallow the operation's exception or convert an unknown outcome to success.

## Native timing protocol version 1

Only a traced request sends `X-VisionTest-Trace` with the value
`v1;<invocation-id>;<request-span-id>`. IDs are 32 lowercase hexadecimal characters.
The existing JSON-RPC body and ID are untouched. Invalid or oversized trace
headers are ignored while normal operation validation still applies.

A supporting server returns `X-VisionTest-Timing`, containing base64url-encoded
UTF-8 JSON with fields `version`, `invocationId`, `requestSpanId`, `spans`, and
`missingStages`. Each native span has an ID, parent ID, fixed stage, request-relative
start offset and duration, outcome, and the same allowlisted numeric metrics.
Limit the encoded header to 8 KiB and 32 spans. Aggregate repeated polls rather
than allocating a span per poll. Unknown SDK internals appear in `missingStages`.

Native stages cover decoding, queue wait where an explicit queue exists, operation,
explicit UI synchronization, polling, screenshot capture, PNG/base64 encoding,
and response serialization. Boundaries that the platform API combines must be
reported as combined, with unavailable sub-stages explicit. Do not report zero
for work that is unmeasured or inapplicable. Freeze the body before timing metadata
is encoded; header encoding and socket write remain outside service duration.

The host imports only version-1 metadata whose IDs match the request and whose
durations, offsets, parents, counts, and size are valid. Validate cycles and
duplicate IDs. Unknown stages are rejected. Missing, invalid, mismatched, or
oversized metadata records an explicit native-timing status and keeps the original
operation response. Older servers work with host-only tracing without a probe,
new endpoint, or retry. Tracing disabled sends and expects no extra metadata.

On iOS, carry the request timing context into `DispatchQueue.main.async`; never
store it on the shared bridge. If the HTTP wait times out while UI work remains
queued or running, snapshot the available metrics under a lock and mark unfinished
work missing. Late work cannot mutate the sent response or another request's trace.
This work must not claim the timed-out action was cancelled or change dispatch.

## Command benchmark contract

Use a Python standard-library runner under `benchmarks/`. Its manifest contains
argument arrays, platform, fixture identity/revision, setup/reset commands, an
operation and assertion, timeouts, warm-up count, sample count, and invocation mode.
Execute argument arrays directly, never through a shell. Keep manifest contents
and optional sanitized artifacts outside timing files.

For CLI, time process spawn through process exit and complete pipe draining. Each
sample launches a new JVM. Warm CLI samples mean warm device/server/OS caches,
not a persistent JVM. For MCP, separately record process startup, initialization,
tool discovery, first call, and calls through the same initialized process.
Native-cold runs restart the native server outside the timed command and identify
that policy separately. Do not claim an OS page-cache cold start without control.

Setup/reset runs before every warm-up and measured sample and outside its timing.
Require an explicit controlled fixture target and setup success before mutating
operations. Support device information, lookup, controlled interaction, appearance
and disappearance waits, inspection, and screenshots on both platforms. Supply a
synthetic app for each platform with stable selectors and deterministic reset.

Export raw samples and a summary. Include build revision, dirty-tree status, JVM,
OS, device/simulator model and OS, connection, fixture revision, server versions,
sample/warm-up policy, tracing state, and unavailable metadata reasons. Keep local
environment metadata separate from privacy-restricted timing events.

Report median and nearest-rank p95, requested/completed/setup-failed samples,
operation errors, timeouts, trace completeness, and counts for each outcome.
Report successful-command latency and all-attempt latency separately. Failed
samples remain in raw output. Validate expected structured outcomes, not merely
exit code 0 or absence of MCP `isError`.

Compare tracing on/off with interleaved order and matched fixture state. Report
absolute differences and ratios with sample counts; do not bake hardware latency
limits into unit tests. Establish the existing HTTP baseline without assuming
connection reuse, WebSocket, or a flow command is implemented.

## Agent evaluation contract

An external adapter receives a versioned case file and emits versioned local JSONL
events. It launches a pinned agent/client against the fixture and records tool
calls, inspections, retries, image-consumption events, and usage where observable.
The harness executes setup and a separate deterministic postcondition verifier.
The agent's final assertion is recorded separately from observed success.

Support an external adapter command without requiring provider credentials in
VisionTest. No automatic transcript persistence or network telemetry is added.
An adapter may invoke a user-configured agent provider; the timing collector does
not transmit data. Real adapters must document their client version and accounting
boundary. A mock adapter proves the protocol, not real agent efficiency.

Store measured input/output/cache usage separately from estimates. Record whether
cached tokens are a subset of reported input tokens; never add overlapping counts.
Unavailable metrics are null with a reason. Token estimates require an explicit
tokenizer name/version and text scope. Never derive image tokens from PNG size or
count a saved screenshot as an image consumed by the agent.

Per run, record latency, tool calls, retries, image consumption, observed outcome,
agent-claimed outcome, and false-positive claims. Preserve failures and ambiguous
outcomes. Report total resources across all attempts and resources divided by the
number of correctly completed flows. If there are no successes, the latter is
unavailable, not zero. Also report distributions for successful runs separately.

Use Android/iOS cases covering successful navigation, failed and ambiguous targets,
appearance/disappearance, and errors resembling success. Reuse these cases across
optimizations. Pin language, agent/model, prompt, client tool-loading/cache policy,
fixture version, and environment. Model experiments add startup/warm inference
timing and peak memory without adding a production inference runtime here.

## Acceptance gates

1. Deterministic clock and fake-sink tests establish duration, nesting, privacy,
   concurrent isolation, queue overflow, writer failure, and bounded drain.
2. CLI/MCP tests preserve output, mapped exits, validation before device access,
   tool discovery, and single dispatch on errors. No new MCP tool is introduced.
3. Android and Swift tests plus both Kotlin clients cover native correlation,
   old-server compatibility, malformed metadata, timeout, and request overlap.
4. Runner tests preserve setup failures and operation failures, compute summaries,
   separate cold/warm modes, and record unavailable metrics explicitly.
5. Real command and agent runs on both platforms produce baseline artifacts and
   an instrumentation-overhead report. Missing hardware/client access leaves
   this gate open; mock results cannot close it.
6. Update CLI/help, README, CONTRIBUTING, relevant contracts, native bundle guidance,
   and self-contained distributable instructions in each applicable delivery.
7. Run `./gradlew :app:test`, `./gradlew :app:e2eTest`, `./gradlew build`, and, for
   native iOS changes, the repository's `xcodebuild test` gate. Keep coverage and
   lint baselines unchanged and `git diff --check` clean.

## Adoption decisions

After baseline collection, record numerical limits for complete-flow regression,
false positives, median/p95 latency, memory, and model error versus coverage in a
versioned evaluation policy. Freeze this policy before held-out evaluation in #64.
No percentage improvement, model selection, or accuracy threshold is invented in
this planning document. A no-adoption result is an acceptable #64 outcome.
