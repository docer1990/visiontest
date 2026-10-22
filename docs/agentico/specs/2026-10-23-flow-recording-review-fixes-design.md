# Flow recording review corrections

Approved on 2026-10-23 after evaluation of the three comments on PR #82.
This restores the recording contract under TD-018 and TD-019.

## Android discovery

Accept the diagnostic suffix on an ADB `no permissions` entry. Exclude that
device and preserve first-online selection. An inventory containing only
unusable devices retains missing-device classification. Reject malformed entries
and diagnostic suffixes on other states as before.

## Startup recovery diagnostics

Keep the startup error, exit code, skipped steps and `start` failure phase.
When finalization of a partially started recorder fails, include its diagnostic
in `recording.error` alongside the startup failure. Preserve any remote path and
serial required for recovery in JSON, text and stderr. A successful cleanup
must not add a spurious cleanup error.

## Shared cleanup deadline

Command cancellation must not grant a fresh 1000 ms timeout beyond the supplied
deadline. Reserve termination and process-exit waiting time inside that deadline.
Force termination must still be attempted if launch or cancellation has already
exhausted the waiting budget. Waiting for exit remains bounded by the remaining
shared budget. Keep cancellation propagation and owned-process isolation.

## Verification

Use plain TDD with focused regressions in the existing parser, startup report,
process runner and cancellation suites. Test short remaining deadlines and delayed
process exit, rather than only instant fake termination. Preserve the existing
expired-launch cleanup regression. Use virtual clocks and synchronization gates
where possible. Run the app unit tests, packaged E2E tests, build gate and
`git diff --check` before updating PR #82.

## Git workflow

Continue on `feat/flow-recording`, based on `main`, using the approved Agentico
default workflow. Commit each correction independently and update the existing PR.
