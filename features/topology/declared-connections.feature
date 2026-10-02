Feature: Declared connections in a service's topology
  A service's topology shows its components and the connections they declared when they were
  registered: what each view and consumer reads, and what a consumer publishes to. Declared
  connections are exact and complete, and they are there before the service has handled anything.

  Scenario: a view is connected to the event sourced entity whose events it reads
    Given a service with an event sourced entity "cart"
    And a view "carts-by-customer" that reads the events of "cart"
    When a developer reads the service's topology
    Then the topology shows "cart" as an event sourced entity
    And the topology shows "carts-by-customer" as a view
    And the topology shows a declared connection from "cart" to "carts-by-customer" as an event subscription

  Scenario: a consumer is connected to the topic it reads and to the topic it publishes to
    Given a service with a consumer "shipper" that reads the topic "orders" and publishes to the topic "shipments"
    When a developer reads the service's topology
    Then the topology shows the topic "orders" and the topic "shipments"
    And the topology shows a declared connection from "orders" to "shipper" as a topic subscription
    And the topology shows a declared connection from "shipper" to "shipments" as a topic publication

  Scenario: a view of a key value entity's state is connected as a state subscription
    Given a service with a key value entity "profile"
    And a view "profiles-by-city" that reads the state of "profile"
    When a developer reads the service's topology
    Then the topology shows a declared connection from "profile" to "profiles-by-city" as a state subscription

  Scenario: a service that has handled nothing shows every declared connection and no observed call
    Given a service with an event sourced entity "cart"
    And a view "carts-by-customer" that reads the events of "cart"
    And the service has handled nothing since it started
    When a developer reads the service's topology
    Then the topology shows a declared connection from "cart" to "carts-by-customer" as an event subscription
    And the topology shows no observed call

  Scenario: a source that is not one of the service's components is shown as outside the service
    Given a service with a view "orders-by-customer" that reads the events of "order"
    And the service has no component "order"
    When a developer reads the service's topology
    Then the topology shows "order" as a component outside the service
    And the topology shows a declared connection from "order" to "orders-by-customer" as an event subscription

  Scenario: a service with only an endpoint shows the endpoint and its routes
    Given a service with an endpoint that serves the route "GET /status/health" and no other component
    When a developer reads the service's topology
    Then the topology shows the endpoint with the route "GET /status/health"
    And the topology shows no declared connection

  Scenario: a service with no endpoint shows none
    Given a service with an event sourced entity "cart" and no endpoint
    When a developer reads the service's topology
    Then the topology shows "cart" as an event sourced entity
    And the topology shows no endpoint
