# Structured CLI response validation

Issue #54 requires command-specific validation before structured inspection output reaches stdout. The user approved this design on 2026-09-10.

The shared inspection formatter receives the command and platform. It validates the required fields in docs/cli-json.md and the types of documented optional fields when present, including interactive element objects. Unknown additive fields remain intact. Missing optional fields remain valid. Invalid fields, null values in typed fields, and malformed structures raise a generic command failure, with exit 1, stderr diagnostics, and empty stdout. Default text output remains unchanged.

Valid RPC errors retain the existing object shape {"error":{"code":integer,"message":string}} and exit 0. Optional RPC data and unknown error fields remain intact. Invalid error objects and envelopes containing both error and result fail with exit 1. TD-011 records the compatibility decision.

Tests use the existing Kotlin test framework and mock HTTP servers. TDD covers both platforms, every required field, scalar and container types, optional and unknown fields, RPC errors, output channels, and text compatibility. No Gherkin framework or native server changes are needed.
