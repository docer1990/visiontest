---
applyTo: "**"
excludeAgent: "cloud-agent"
---

# Code review

Review changes against `AGENTS.md`, the repository contracts in
`.github/copilot-instructions.md`, and the relevant specs. Follow affected code
paths and callers before concluding that a change violates a contract.

- Report actionable problems introduced or exposed by the change. Identify the
  exact changed lines, a concrete triggering scenario, and the user or operational
  impact. Explain the causal link; distinguish evidence from assumptions.
- Prioritize security vulnerabilities, incorrect results, data loss, races,
  compatibility regressions, and demonstrated performance regressions. Assign
  severity according to impact and likelihood rather than treating every logic
  error as critical.
- For shell/process invocation, check argument boundaries and quoting at the
  actual execution point. Validate inputs according to their domain; do not
  require arbitrary user text to belong to a fixed allowlist. Check serialization,
  credential exposure, resource cleanup, and cancellation where relevant.
- Check tests for the changed behavior and important failure paths. Flag a
  concrete missing regression case, rather than requesting tests for every edit.
  Do not claim tests passed unless execution evidence is available.
- For performance changes, check measurement boundaries, missing stages, attempt
  accounting, and whether instrumentation introduces extra work or retries.
  Do not infer a speedup from incomplete or incompatible populations.
- Keep one finding per root cause, use the smallest useful line range, and suggest
  a focused fix when the correct approach is clear. Avoid duplicate findings.
- Do not comment solely on preferred syntax, function length, generic SOLID/DRY
  advice, missing KDoc, TODO formatting, or pre-existing issues unaffected by the
  change. Skip praise-only comments and speculative optimizations. It is valid
  to report no findings when no actionable problem is supported by the evidence.
