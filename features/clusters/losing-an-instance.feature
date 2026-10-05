Feature: Losing an instance of a service cluster
  An instance can stop without warning. The other instances remove it and go on serving, its
  entities answer again from them with nothing lost, and its replacement joins the service cluster
  that is there. Instances cut off from one another never go on as two service clusters.

  Background:
    Given a deployed service "cart" with 3 instances in one service cluster

  Scenario: an instance that stops without warning is removed and the others go on serving
    When one instance of "cart" stops without warning
    Then the other 2 instances remove it from the service cluster
    And "cart" goes on answering requests

  Scenario: an entity on a lost instance answers from another with nothing lost
    Given the cart "c1" holds the item "socks" and is active on one instance
    And that instance has stopped without warning
    When the cart "c1" is read
    Then another instance answers, and the cart "c1" shows the item "socks"

  Scenario: the replacement of a lost instance joins the service cluster that is there
    Given one instance of "cart" has stopped without warning
    When its replacement starts
    Then the replacement joins the service cluster of the other 2 instances
    And "cart" has one service cluster of 3 instances

  Scenario: instances cut off from one another go on as exactly one service cluster
    Given the instances of "cart" are cut off from one another, 2 on one side and 1 on the other
    When they can reach one another again
    Then only one side went on as the service cluster of "cart"
    And "cart" has exactly one service cluster
