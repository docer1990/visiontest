Feature: Required screen recording for flows

  Scenario: Save the requested flow recording
    Given a valid flow with recording requested
    When the recorder starts and the flow succeeds
    Then the flow succeeds with a saved video
    And the video includes the requested final observation

  Scenario: Reject a flow whose recorder cannot start
    Given a valid flow with recording requested
    When the recorder cannot start
    Then the flow fails with the recording error
    And every flow step is skipped
    And no observations or failure evidence are collected

  Scenario: Stop a flow when its recorder exits early
    Given a recorded flow with an action awaiting its response
    When the recorder exits before the flow finishes
    Then the flow fails with the recording error
    And the pending action has an unknown effect
    And later steps are skipped without replay

  Scenario: Preserve the original flow failure
    Given a recorded flow whose first action has failed
    When the recorder also fails during failure evidence collection
    Then the original action error remains the primary failure
    And the recording error is reported separately
    And failure evidence is collected only once

  Scenario: Fail a flow whose video cannot be saved
    Given a recorded flow whose steps have succeeded
    When the video cannot be saved correctly
    Then the flow fails with the recording error
    And the completed steps remain successful
    And no invalid video path is reported

  Scenario: Save the video after the flow deadline expires
    Given a recorded flow with a short execution deadline
    When the execution deadline expires during an action
    Then the flow retains its timeout failure
    And the recorder stops within its separate finalization budget
    And a valid video path is reported
