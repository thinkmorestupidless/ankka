Feature: Pausing, resuming, restarting and deleting a deployed service
  What a member asks of a deployed service happens in the cluster: a paused service has no
  instances and keeps its descriptor, a restarted one has new instances and the same descriptor,
  and a deleted one leaves nothing behind.

  Scenario: a paused service has no instances and keeps its descriptor
    Given a deployed service "cart" that is ready
    When a member pauses "cart"
    Then the status of "cart" is "Paused" and "cart" has no instances
    And the descriptor of "cart" is kept

  Scenario: a resumed service is ready again
    Given a paused service "cart"
    When a member resumes "cart"
    Then "cart" is ready

  Scenario: a restarted service has new instances and the same descriptor
    Given a deployed service "cart" that is ready
    When a member restarts "cart"
    Then every instance of "cart" started after the restart
    And the descriptor of "cart" is unchanged
    And "cart" is ready

  Scenario: a deleted service leaves nothing in the cluster
    Given a deployed service "cart" that is ready
    When a member deletes "cart"
    Then nothing the platform made for "cart" is left in the cluster

  Scenario: a descriptor applied to a paused service takes effect when it is resumed
    Given a paused service "cart" with the image "cart:1"
    When a member applies the descriptor of "cart" with the image "cart:2"
    Then the status of "cart" is "Paused" and "cart" has no instances
    And "cart" runs the image "cart:2" once it is resumed
