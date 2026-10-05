Feature: The documentation of autonomous agents
  A developer who was not told what an autonomous agent does when its process stops, or what it does
  not do yet, finds out from a deployed service.

  Scenario: the documentation explains autonomous agents on a page that stands alone
    Given the published documentation
    When a developer reads about autonomous agents
    Then the documentation explains the autonomous agent, the task, the iterations, the budget and what happens when a process stops
    And the documentation says when to choose an autonomous agent over a request agent or a workflow

  Scenario Outline: the documentation shows how to build an autonomous agent in each language from tested code
    Given the published documentation
    When a developer follows the documentation to build an autonomous agent in "<language>"
    Then every sample the developer copies is included from tested code

    Examples:
      | language |
      | Scala    |
      | Python   |

  Scenario: the documentation says what autonomous agents do not do
    Given the published documentation
    When a developer reads the limitations of autonomous agents
    Then the documentation says that an autonomous agent does not delegate, hand a task on, lead a team or moderate a conversation
    And the documentation says that an autonomous agent has only the tools declared with it, and that one agent instance cannot override its definition
    And the documentation says that the "TypeScript" test kit cannot script an autonomous agent's model

  Scenario: the documentation lists every difference from Akka's autonomous agents with its reason
    Given the published documentation
    When a developer reads how the platform differs from Akka
    Then every deliberate difference of autonomous agents is listed with its reason
