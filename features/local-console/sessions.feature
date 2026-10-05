Feature: Reading an agent's session in the local console
  The local console shows a session of an agent as the platform stores it, and the model usage the
  session recorded. It shows cost as unknown, because the platform is told no prices.

  Scenario: the local console shows an agent's session and its model usage, with cost unknown
    Given a service running on a developer's machine with an agent "helper"
    And "helper" has held the session "s1"
    When the developer reads the session "s1" in the local console
    Then the local console shows "s1" whole, as the platform stored it
    And it shows the model usage "s1" recorded
    And it shows the cost of "s1" as unknown
