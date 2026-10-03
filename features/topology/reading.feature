Feature: Reading a service's topology
  A service with many components is read a part at a time: without the platform's own components,
  around one component, or by kind of component. Leaving something out never loses a connection
  or a call. From a service's topology a developer goes to the topology of a service it called,
  when that service is running on the same machine.

  Scenario: platform components are left out until a developer asks for them
    Given a service with an agent "helper" that calls the platform component "ankka-session-memory"
    And "helper" has called "ankka-session-memory"
    When a developer reads the service's topology
    Then the developer is not shown "ankka-session-memory"
    And the developer is shown that "helper" made a call through a platform component

  Scenario: a developer who asks for platform components is shown them, marked as the platform's
    Given a service with an agent "helper" that calls the platform component "ankka-session-memory"
    And "helper" has called "ankka-session-memory"
    When a developer reads the service's topology with its platform components
    Then the developer is shown "ankka-session-memory" marked as a platform component
    And the developer is shown an observed call from "helper" to "ankka-session-memory"

  Scenario: focusing on a component shows it and what it is connected to, and nothing else
    Given a service with an event sourced entity "cart" and a key value entity "profile"
    And a view "carts-by-customer" that reads the events of "cart"
    When a developer focuses the topology on "cart"
    Then the developer is shown "cart" and "carts-by-customer"
    And the developer is not shown "profile"

  Scenario: reading one kind of component leaves the other kinds out
    Given a service with an event sourced entity "cart" and a key value entity "profile"
    And a view "carts-by-customer" that reads the events of "cart"
    When a developer reads the topology with only its views
    Then the developer is shown "carts-by-customer"
    And the developer is not shown "cart"
    And the developer is not shown "profile"

  Scenario: a called service running on the same machine under one name is opened from its caller's topology
    Given a service "checkout" with an endpoint that has called the service "orders"
    And exactly 1 service named "orders" running on the developer's machine
    When the developer opens "orders" from the topology of "checkout"
    Then the developer is shown the topology of "orders"

  Scenario: a called service that is not running on the developer's machine is marked as not running here
    Given a service "checkout" with an endpoint that has called the service "orders" 3 times
    And no service named "orders" running on the developer's machine
    When the developer reads the topology of "checkout"
    Then the topology of "checkout" marks "orders" as not running here
    And the observed call from the endpoint to "orders" is handled 3 times, as ok
