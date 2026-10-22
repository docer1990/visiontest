# Symmetric CLI wait output

Issue #53 completes the structured CLI action workflow after #49.

The standalone `wait_for_element` command uses the existing registrar `waitResult`
operation for both platforms and modes. Default successful output is `Element
appeared.` or `Element gone.`. Opt-in `--json` emits one unwrapped find result,
preserves additional fields, and adds `condition` (`appeared` or `gone`) and
boolean `satisfied`, following the existing flow wait schema. A successful gone
result includes `found:false`, `condition:"gone"`, and `satisfied:true`.

Validate returned results using the existing platform find-result validators.
Timeouts, RPC failures, transport failures and malformed responses remain thrown
failures with empty stdout and the existing stderr/exit mapping. Invalid input
fails before component creation. Do not turn timeout into a normally returned
JSON result or reinterpret a server failure as disappearance. Preserve the
existing distinction between normally returned operation results and exceptions.

MCP output, polling, native servers and flow output remain unchanged. Step-level
`--json` remains invalid inside flow. Reuse TD-003 and TD-006; no new architectural
choice is needed. Test with plain Kotlin TDD and MockWebServer fixtures, covering
both platforms, both modes, text/JSON, invalid inputs, malformed responses,
timeouts, errors, additive fields and installed CLI output.
