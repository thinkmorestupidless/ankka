Feature: The documentation of workflow sources
  A developer learns from the documentation that a view or a consumer can read a workflow, and what a
  change from one carries.

  Scenario: the documentation names a workflow among the sources of a view and a consumer
    Given the published documentation
    When a reader reads about the sources of a view or a consumer
    Then the documentation names a workflow among them
    And the documentation says a change from a workflow carries the state and the standing
    And the documentation says a transition, pause, end or failure that records no state is no change

  Scenario: the documentation of workflows says a standing can be read as it changes
    Given the published documentation
    When a reader reads about a workflow's standing
    Then the documentation says a view or a consumer can read it as it changes
    And the documentation says a step whose outcome must be seen records it in the state
    And the documentation says where to read how a view or a consumer reads a workflow
