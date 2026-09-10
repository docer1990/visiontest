---
id: TD-011
title: "Preserve returned RPC errors in CLI JSON"
status: accepted
date: 2026-09-10
supersedes: null
superseded_by: null
tags: [cli, json, errors]
---

# TD-011: Preserve returned RPC errors in CLI JSON

## Context
Structured inspection already emits valid RPC errors as an error object with exit 0.
The command schemas did not document this outcome. Issue #54 requires a defined
error contract alongside validation of command results.

## Decision
Keep the existing error object and exit 0 for valid returned RPC errors.
Document this as an alternative to each inspection command's result schema.

## Rationale
This preserves existing scripts and the CLI distinction between normally returned
operation failures and thrown failures. The user approved this compatibility choice.

## Consequences
Scripts must inspect the error field as well as success or found.
Malformed RPC errors still fail with exit 1 and empty stdout.
The shared formatter owns error validation for all inspection commands.

## Alternatives considered
Converting every RPC error to exit 1 would make shell checks simpler, but would
change the existing returned-failure convention and discard the current JSON output.
