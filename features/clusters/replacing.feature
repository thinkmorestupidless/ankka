Feature: Replacing and scaling the instances of a service cluster
  A change to a deployed service replaces its instances by a rolling update: a new instance joins
  the service cluster and takes over before an old one leaves it. The service answers every request
  that only reads throughout, and never has two service clusters. The platform delivers a call to an
  entity at most once while the entity moves from one instance to another, so a call can be lost
  then: a query that goes unanswered is sent again, and a command, which may have run, is not.

  Scenario Outline: a service whose image changes refuses no request
    Given a deployed service "cart" with the image "cart:1" and <instances>
    And requests that only read a cart sent to "cart" one after another
    When a member applies the descriptor of "cart" with the image "cart:2"
    Then "cart" is replaced by a rolling update
    And every request sent to "cart" is answered
    And "cart" never has more than one service cluster

    Examples:
      | instances   |
      | 1 instance  |
      | 3 instances |

  Scenario: an instance being replaced leaves its service cluster and its entities go on elsewhere
    Given a deployed service "cart" with 3 instances and the cart "c1" active on one of them
    When that instance is stopped to be replaced
    Then the instance leaves the service cluster before it stops
    And the cart "c1" answers from another instance with every item it held

  Scenario: a query lost while its entity moves to another instance is sent again and answered
    Given a deployed service "cart" with 3 instances and the cart "c1" active on one of them
    When that instance is stopped to be replaced
    And a query of the cart "c1" is lost on its way to that instance
    Then the query is sent again
    And it is answered from another instance with every item the cart held

  Scenario: a command lost while its entity moves to another instance is not sent again and times out
    Given a deployed service "cart" with 3 instances and the cart "c1" active on one of them
    When that instance is stopped to be replaced
    And a command for the cart "c1" is lost on its way to that instance
    Then the command is not sent again
    And its caller has timed out

  Scenario: instances added to a service join its service cluster and replace none
    Given a deployed service "cart" with 3 instances
    When a member scales "cart" to 4 instances
    Then "cart" has one service cluster of 4 instances
    And the 3 instances that were running are still running

  Scenario: instances taken from a service leave its service cluster before they stop
    Given a deployed service "cart" with 4 instances
    When a member scales "cart" to 2 instances
    Then the 2 instances taken away leave the service cluster before they stop
    And "cart" has one service cluster of 2 instances

  Scenario: an instance that has not joined the service cluster is not ready
    Given a deployed service "cart" with 3 instances
    When an instance of "cart" has started and has not joined the service cluster
    Then the instance is not ready
    And no request is sent to the instance

  Scenario: a service with some of its instances ready is partially ready, with the counts
    Given a deployed service "cart" that asks for 3 instances, 2 of them ready
    When a member reads the report of "cart"
    Then the lifecycle of "cart" is "PartiallyReady"
    And the report shows 2 of 3 instances ready
