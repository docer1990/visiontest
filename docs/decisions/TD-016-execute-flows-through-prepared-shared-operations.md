---
id: TD-016
title: "Execute flows through prepared shared operations"
status: accepted
date: 2026-10-19
supersedes: null
superseded_by: null
tags: [cli, automation, results, timeouts]
---

# TD-016: Execute flows through prepared shared operations

## Context
Individual commands mix parsing, validation, operation execution and presentation.
A flow must validate every step before performing any action and cannot infer
operation success or saved paths from prose. Issues #49, #52 and #53 add separate
command output modes but are not prerequisites for #56.

## Decision
Prepare validated operations using shared CLI definitions and run them through one
component holder and the existing registrars. Add structured internal results where
needed while retaining existing presentation adapters. Scope transport deadlines
and dispatch evidence to each flow execution.

## Rationale
This preserves TD-003 and TD-006, avoids duplicated validation and polling, and
lets future standalone JSON modes reuse the same operation results.

## Consequences
- Flow can report returned failures independently of standalone exit conventions.
- Shared internals require regression tests for existing CLI and MCP behavior.
- Unknown action effects remain explicit after transport failures.

## Alternatives considered
Parsing CLI prose would couple correctness to presentation and lose certainty.
Launching subprocesses would prevent component reuse and add process startup cost.
Completing all standalone JSON commands first would delay flows unnecessarily.
