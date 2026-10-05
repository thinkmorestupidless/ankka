Feature: A service cluster of several instances
  The instances of a deployed service find each other and form one service cluster. Each entity is
  active on exactly one instance, and a request that arrives at any instance reaches it. A service
  runs exactly as many instances as its descriptor asks for.

  Scenario: the instances of a service form one service cluster
    When a member applies a descriptor for the service "cart" that asks for 3 instances
    Then 3 instances of "cart" are running
    And all 3 are in one service cluster

  Scenario: a request reaches an entity whichever instance it arrives at
    Given a deployed service "cart" with 3 instances in one service cluster
    And the item "socks" has been added to the cart "c1" through one instance
    When the cart "c1" is read through another instance
    Then the cart "c1" shows the item "socks"

  Scenario: services whose instances all start at the same moment each form exactly one service cluster
    Given 10 services deployed for the first time, each with 3 instances
    When all their instances start at the same moment
    Then each of the 10 services has exactly one service cluster

  Scenario: a descriptor that says nothing of instances runs one, however busy the service is
    When a member applies a descriptor for the service "cart" that says nothing about instances
    Then 1 instance of "cart" is running
    And the number of instances of "cart" does not change with how many requests it is sent

  Scenario: the instances of two services in one project never join each other's service cluster
    Given the services "cart" and "orders" in the project "shop", each with 3 instances
    When a member applies the descriptors of both
    Then "cart" and "orders" each have one service cluster of 3 instances
    And no instance of "cart" is in the service cluster of "orders"
