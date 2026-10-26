# Persistent Kotlin CLI daemon

## Status and scope

The user approved automatic daemon execution on 2026-10-26 for issue #86.
TD-020 records the architecture. This delivery preserves the shared operations
from TD-003 and the flow engine from TD-016. It changes host process lifetime,
not native RPC methods, UI observation freshness or MCP execution.

## Entry points

Ordinary device commands use a local daemon and start it automatically if absent.
The root `--standalone` switch selects execution in the calling JVM. It is only
recognized as a root option, never inside typed input or flow step text.
`daemon start`, `daemon status` and `daemon stop` require no platform or device.
No arguments and first-argument `serve` retain MCP behavior. Root help/version
and `init` execute locally without contacting or starting the daemon. Help for a
device command is rendered without initializing device components.

The forwarding client must not construct the full CLI command tree or initialize
the terminal framework or device graph. Authoritative parsing and validation use
the existing command definitions in the host. Existing exit codes, stdout/stderr,
one-object JSON framing, and returned versus thrown failures remain compatible.
Infrastructure failures report diagnostics on stderr with exit 1 and no fake
operation result. `--standalone` never activates an implicit fallback.

## Local IPC and lifecycle

One daemon per operating-system user coordinates the fixed native endpoints.
A Unix-domain socket, instance metadata and lock live in a private user-owned
runtime directory. The directory must reject symbolic links, foreign ownership
and group/other access. The socket and metadata are accessible only to its owner.
A test-only runtime-directory override permits process tests without touching a
developer's running daemon. Unsupported socket paths fail with a useful diagnostic.

Messages are length-delimited UTF-8 JSON with explicit protocol version, request
identity and message kind. Clients verify the protocol and application build
identity before submitting work. Incompatible live daemons are reported, never
silently killed or replaced. Build identity distinguishes different development
builds carrying the same application version. A stale socket is removed only
under the instance lock after establishing that no live owner holds that lock.

The starting daemon holds the instance lock for its lifetime. Concurrent clients
wait for readiness for at most 15 seconds and converge on one instance. Startup
diagnostics stay off operation stdout. Lifecycle status reports running/stopped,
PID, compatibility and reuse counters without contacting a native backend.

The daemon expires after 15 minutes with no active request or queued work. Stop
rejects new requests, drains accepted work for up to 30 seconds, then cancels it
and performs bounded cleanup. It closes host connections, trace sinks, recording
processes and owned child processes. It never terminates unrelated xcodebuild,
ADB servers or native backends merely because they use a known port. Android
instrumentation remains separately controlled by the existing native stop command.

## Request isolation and reuse

Each request has fresh parser instances, an execution context and a result sink.
The context contains an absolute caller directory, relevant environment values,
terminal presentation context, stdin access, request identity and tracing state.
Flow input, screenshots, recordings, artifact discovery and trace paths resolve
against the caller directory. Child processes use explicit cwd/environment.
Argument-file expansion must also resolve against the caller directory.

Neither standard streams nor JVM working-directory/environment properties may be
swapped per request. Parsing, operations and handled diagnostics return through
request-owned sinks. Only the outer client/standalone gateway exits the JVM.
Standard input is requested only when the host actually needs it, so ordinary
commands and invalid flows do not consume stdin. The whole existing flow engine,
including preflight, observations, recording and finalization budgets, is reused.

Retained components own only reusable transport/manager resources and registrar
state. A request cannot reuse a sealed trace recorder or another caller's logger,
artifact search roots or environment. Request-bound adapters may be recreated.
No request adds an enduring shutdown hook, output buffer or completed-result entry.
Tracing remains opt-in and records component initialization only when it occurs.

## Target admission and interruption

The daemon serializes device requests, including each whole flow, so its clients
cannot interleave stateful operations. Admission permits at most 32 queued device
requests; queue wait is bounded to 30 seconds. Busy/expired requests fail before
dispatch. Lifecycle status and cancellation remain responsive during device work.

After argument/preflight validation, target checks require exactly one usable
Android device or one booted iOS simulator for the selected platform. Android
forwarding must agree with the admitted serial. Ambiguity is rejected before an
action; a platform flag or cached target is not routing evidence. Target checks
and existing operation health checks are not elided for performance claims.
External standalone/MCP clients are outside the daemon's mutual-exclusion boundary.

Reuse is invalidated by target changes, disconnects, incompatible configuration,
or failed native transport. Native restarts are handled by fresh connections and
normal readiness checks; no UI result is cached. An invalid request may encounter
already-existing infrastructure, but must not initialize new components or touch
the backend. Configurations requiring replacement are handled only when no request
uses the old components, and owned resources are closed exactly once.

Cancellation/disconnect removes queued work or cancels active host execution.
Host cancellation does not establish that a native action stopped. The daemon
keeps the target execution boundary until host work and cleanup have settled.
If native completion cannot be established after dispatch, later mutations are
rejected until explicit recovery, instead of silently interleaving with that work.
Known results remain known; a lost response reports uncertainty and never causes
a retry, crash resume or standalone fallback. Requests and I/O waits have finite
limits; long flows retain their documented execution and finalization budgets.

Protocol request/stdin messages are limited to 8 MiB. Output is sent in bounded
chunks; any overall output limit must fail explicitly without truncating a valid
JSON result or claiming an undispatched operation. Daemon logs use bounded storage.

## Verification and measurement

Use plain TDD with focused unit tests and real-process acceptance tests. Tests
exercise reuse, startup races, version mismatch, stale registration, access checks,
request isolation, invalid input before initialization, lifecycle cleanup, target
changes, backend restarts, cancellation and no replay. Existing CLI/MCP and flow
tests remain regression coverage. Process tests use a private runtime directory
and terminate only the daemon they created.

Extend the benchmark harness for standalone versus daemon commands and flows.
Keep operation parameters, native request counts and output equivalent, and report
any admission overhead separately. Measure cold daemon startup, first device use,
fresh client process wall time and warm host execution. Retain raw samples,
failures/timeouts/uncertain results, median/p95, idle RSS and repeated-request RSS.
Android and iOS fixture measurements are required evidence; missing device access
must be reported as an outstanding acceptance item rather than fabricated results.

Run `./gradlew :app:test`, `./gradlew :app:e2eTest`, `./gradlew build`, the affected
Python harness tests and `git diff --check`. Update the CLI/lifecycle contracts,
README, contributor guidance and the self-contained distributable skill.
