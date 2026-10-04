Feature: The topology of a deployed service
  A member of a project reads the topology of a service deployed in it: the same components,
  declared connections and observed calls a developer reads on their own machine, with the
  observed calls of every instance added together. The topology says how many instances answered,
  and one that is missing an instance is never shown as whole.

  Scenario: a member of a project reads the topology of a service deployed in it, and is given no credential
    Given a service "cart" deployed in the project "checkout"
    And a member of "checkout"
    When the member reads the topology of "cart"
    Then the member is shown the components, the declared connections and the observed calls of "cart"
    And the member is given no credential for the service, its instances or the cluster
    And the topology is a section of the page of "cart", reached as a page of its own

  Scenario: observed calls are added together across a service's instances
    Given a service "cart" deployed in the project "checkout" with 3 instances
    And each instance has handled 2 calls from its endpoint to the event sourced entity "cart"
    When a member of "checkout" reads the topology of "cart"
    Then the observed call from the endpoint to "cart" is handled 6 times, as ok
    And the topology says that 3 of 3 instances answered

  Scenario: an instance that does not answer leaves the topology partial
    Given a service "cart" deployed in the project "checkout" with 3 instances
    And 1 instance that does not answer
    When a member of "checkout" reads the topology of "cart"
    Then the topology is marked as partial
    And the topology names the instance that did not answer
    And the topology says that 2 of 3 instances answered

  Scenario: an instance too old to report its topology is named as unsupported
    Given a service "cart" deployed in the project "checkout" with 2 instances
    And 1 instance too old to report its topology
    When a member of "checkout" reads the topology of "cart"
    Then the topology is marked as partial
    And the topology names that instance as unsupported

  Scenario: a person who is not a member of the project is refused as if the service did not exist
    Given a service "cart" deployed in the project "checkout"
    And a person who is not a member of "checkout"
    When the person reads the topology of "cart"
    Then the person is refused
    And the refusal is the one given for a service that does not exist

  Scenario: a component that only some instances have is shown with the instances that have it
    Given a service "cart" deployed in the project "checkout" with 2 instances
    And 1 instance with a view "carts-by-customer" that the other instance does not have
    When a member of "checkout" reads the topology of "cart"
    Then the topology shows "carts-by-customer" as a view
    And the topology names the instance that has "carts-by-customer"
