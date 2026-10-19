Feature: Execute short known UI sequences
  Scenario: Reject an invalid later step before acting
    Given a sequence with a valid tap followed by an invalid command
    When the caller submits the flow
    Then no device components are created and stdout is empty

  Scenario: Execute ordered steps through one session
    Given a valid sequence of actions and an element wait
    When the caller submits the flow
    Then each step succeeds in order using one device session

  Scenario: Stop after an operation failure
    Given a sequence whose second action returns a failure
    When the caller submits the flow
    Then the flow reports the failed step and skips the remaining steps

  Scenario: Preserve uncertainty after a lost response
    Given an action is dispatched and its response is lost
    When the caller submits the flow
    Then its effect is unknown and no action is replayed

  Scenario: Collect bounded failure evidence
    Given a failed action with requested screenshot evidence
    When evidence collection exceeds its budget
    Then the original action error and exit code are preserved

  Scenario: Return the final screen state
    Given successful actions followed by an explicit element wait
    When the caller requests final interactive elements
    Then the flow returns the elements with the completed action results

  Scenario: Report final observation failure separately
    Given all actions succeed and a requested final observation fails
    When the caller receives the report
    Then the flow fails while the completed actions remain successful
