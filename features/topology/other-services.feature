Feature: Calls to other services in a service's topology
  A call from one service to another is an observed call to a service outside the caller. The
  other service is shown by name, up to a limit.

  Scenario: a call to another service is shown as a call to a service outside this one
    Given a service "checkout" with an endpoint that calls the service "orders"
    When the endpoint calls "orders"
    Then the topology of "checkout" shows "orders" as a service outside it
    And the topology of "checkout" shows an observed call from the endpoint to "orders"

  Scenario: services beyond the limit are counted together as other services
    Given a service "checkout" that shows at most 2 other services by name
    And an endpoint that calls the service "orders", the service "billing" and the service "shipping"
    When the endpoint calls "orders", then "billing", then "shipping"
    Then the topology of "checkout" shows "orders" and "billing" as services outside it
    And the topology of "checkout" shows an observed call from the endpoint to other services
    And the topology of "checkout" shows 3 calls from the endpoint in all
