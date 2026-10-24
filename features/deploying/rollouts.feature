Feature: Rolling updates
  Every apply, restart and roll back of a deployed service replaces its instances one at a time: a
  new instance joins the service cluster the old ones form and is ready before an old one stops. A
  generation whose instances never became ready formed no service cluster, so the next generation
  forms its own among its own instances and the old ones are stopped. A generation that has been
  ready is joined, never replaced out of hand.

  Scenario: a generation that works replaces one whose instances never became ready
    Given a deployed service "cart" whose only instance exits shortly after it starts
    And the lifecycle of "cart" is "Failed"
    When a member applies the descriptor of "cart" with an image that runs
    Then "cart" is ready with 1 instance
    And every instance of "cart" runs the new generation
    And no instance of the generation that never became ready is left

  Scenario: the next generation forms its service cluster among its own instances when the running generation never became ready
    Given a deployed service "cart" with 2 instances that exit shortly after they start
    When a member applies the descriptor of "cart" with an image that runs
    Then the instances of the new generation of "cart" form one service cluster
    And no instance of the generation that never became ready is in it

  Scenario: a restart of a service whose instances never became ready gives it instances that form a service cluster
    Given a deployed service "cart" whose only instance exits shortly after it starts
    And what made the instance exit is gone
    When a member restarts "cart"
    Then every instance of "cart" started after the restart
    And "cart" is ready with 1 instance

  Scenario: a generation that also never becomes ready is reported failed with its own reason
    Given a deployed service "cart" whose only instance exits shortly after it starts, at generation 1
    When a member applies the descriptor of "cart" with an image that exits shortly after it starts for another reason
    Then the lifecycle of "cart" is "Failed" at generation 2
    And the report names the reason the instance of generation 2 gives

  Scenario: a generation that works is ready after two that never became ready
    Given a deployed service "cart" whose instances never became ready at generation 1 and at generation 2
    When a member applies the descriptor of "cart" with an image that runs
    Then "cart" is ready with 1 instance
    And the lifecycle of "cart" is "Ready" at generation 3

  Scenario: a generation that has been ready is joined by the next even while one of its instances is not ready
    Given a deployed service "cart" that is ready with 3 instances
    And one instance of "cart" exits and restarts
    When a member applies the descriptor of "cart" with the image "cart:2"
    Then every new instance of "cart" joins the service cluster the instances that were ready form
    And "cart" is never without a ready instance
    And "cart" becomes ready with the image "cart:2"
