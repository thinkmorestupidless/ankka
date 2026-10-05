Feature: Reports of a deployed service
  The operator reports what it sees of each deployed service, naming the generation the report
  describes, and the control plane records it. A report about a generation that has been replaced
  is not recorded, and a report that says nothing new is not recorded either.

  Scenario: a service nobody changes records no new report
    Given a deployed service "cart" that is ready and that nobody changes
    When an hour passes
    Then the control plane records no new report of "cart"
    And what the control plane recorded of "cart" does not grow

  Scenario: a report about a generation that was replaced is not recorded
    Given a deployed service "cart" whose descriptor was applied at generation 4 and again at generation 5
    When a report of "cart" describing generation 4 arrives
    Then the report is not recorded
    And the status of "cart" describes generation 5
