Feature: Inspect UI with less repeated context
  Scenario: Preserve target information in a compact observation
    Given native Android and iOS observations with distinct and duplicate targets
    When the caller requests compact elements
    Then observed selectors and relevant states remain available without repeated presentation fields

  Scenario: Request compact inspection through CLI and MCP
    Given a running automation server with interactive elements
    When the caller requests compact elements through CLI or MCP
    Then both interfaces return the same compact representation and reject invalid filters before device access

  Scenario: Return compact observations from a flow
    Given a flow with requested interactive-element observations
    When the caller enables compact output
    Then final observations and failure evidence use the shared compact representation
