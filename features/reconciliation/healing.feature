Feature: Returning to what was applied
  What the control plane recorded is what a deployed service is made to be. When something in the
  cluster changes behind the platform's back, when the cluster cannot be reached, or when the
  operator or the control plane restarts, the service comes back to it with no member doing
  anything, and nothing is made twice.

  Scenario: a workload removed behind the platform's back is made again
    Given a deployed service "cart" that is ready
    When someone removes the workload of "cart" without going through the platform
    Then the operator makes the workload of "cart" again
    And "cart" is ready with no member doing anything

  Scenario: a descriptor applied while the cluster cannot be reached is kept and deployed once it can be
    Given the control plane cannot reach the cluster
    When a member applies a descriptor for the service "cart"
    Then the descriptor is accepted
    And the lifecycle of "cart" is unconfirmed
    And "cart" is ready once the control plane reaches the cluster again

  Scenario: an operator restarted while a service's instances are replaced finishes without making anything twice
    Given a member has applied a descriptor for "cart" and the instances of "cart" are being replaced
    When the operator restarts
    Then "cart" is ready
    And the cluster holds one copy of everything the platform made for "cart"

  Scenario: a restarted control plane goes on without making anything twice
    Given a deployed service "cart" that is ready
    When the control plane restarts
    Then the cluster holds one copy of everything the platform made for "cart"
    And reports of "cart" are recorded again

  Scenario: a service that always fails holds up no other service
    Given a deployed service "broken" that always fails
    When a member applies a descriptor for the service "cart"
    Then "cart" is ready
    And the lifecycle of "broken" is "Failed"
