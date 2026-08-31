# Android stop port-forward cleanup

## Goal

Make `stop_automation_server` report successful ADB port-forward cleanup only when the forward was removed or was already absent, and cover the host-side ADB command with unit tests.

## Design

`AndroidStopToolRegistrar` will receive an internal suspend-function dependency for host-side ADB commands. Production construction will keep delegating to `Android.executeAdb`; tests will inject a recording implementation. The public MCP and CLI contracts remain unchanged.

Cleanup first executes `forward --remove tcp:9008`. If removal succeeds, execution continues. If it throws `CommandExecutionException`, cleanup executes `forward --list` and checks for the exact local endpoint `tcp:9008`. An absent endpoint means the desired state already holds and preserves idempotency. A still-present endpoint, or an inability to verify the list, causes the original removal error to propagate so the tool cannot claim success incorrectly.

## Testing

Tests will establish these behaviors before production code changes:

- successful stop invokes `forward --remove tcp:9008`;
- a failed removal is tolerated when `forward --list` confirms the endpoint is absent;
- a failed removal propagates when the endpoint remains listed;
- a failed removal propagates when absence cannot be verified.

The focused registrar tests will run after each red/green cycle, followed by the complete `:app:test` suite and relevant static checks.

## Scope

No timeout behavior, MCP schema, CLI syntax, Android command validation, or unrelated registrar logic will change.
