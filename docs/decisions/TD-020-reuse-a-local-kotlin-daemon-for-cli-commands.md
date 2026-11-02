---
id: TD-020
title: "Reuse a local Kotlin daemon for CLI commands"
status: accepted
date: 2026-10-26
supersedes: null
superseded_by: null
tags: [cli, daemon, performance, lifecycle]
---

# TD-020: Reuse a local Kotlin daemon for CLI commands

## Context
Independent CLI calls repeatedly construct the parser, terminal framework and
host resources. Issue #86 addresses this cost while preserving shared operations.

## Decision
Device CLI commands automatically start and reuse a Kotlin daemon by default.
`--standalone` selects the original process lifetime. Local Unix sockets in a
private user directory carry versioned requests. The daemon serializes whole
requests, retains compatible infrastructure and isolates each caller's context.

## Rationale
Automatic use benefits existing agent commands without a new session workflow.
Unix sockets fit the supported macOS/Linux hosts and permit filesystem access
control without a network listener. TD-003 and TD-016 retain the operation boundary.

## Consequences
- Idle expiry and explicit lifecycle commands bound background resource lifetime.
- Version mismatch requires an explicit stop; active work is never replaced.
- Cancellation after possible dispatch never triggers replay or standalone fallback.
- A single target per platform is enforced until reliable multi-device routing exists.
- Measurements must include JVM client startup and daemon memory, not just warm work.

## Alternatives considered
An opt-in daemon would leave existing agent commands on the slower path. Mandatory
manual startup adds a step without solving lifecycle correctness. A native client
adds distribution work before the actual Kotlin client path has been measured.
Loopback TCP adds authentication and port-discovery concerns unnecessary here.
