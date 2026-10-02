Feature: Serving a gRPC endpoint
  A gRPC endpoint implements one service definition. A call to one of its methods is answered by
  the handler the endpoint declares for that method, and a mistake in the endpoint is found when
  the service starts, not by the first call.

  Scenario: a call to a method is answered by the handler the endpoint declares for it
    Given a service "cart" with a gRPC endpoint for the service definition "CartService"
    And the gRPC endpoint declares a handler for the method "GetCart"
    When a developer calls the method "GetCart" of "CartService"
    Then the handler for the method "GetCart" runs
    And the call ends with the status "ok" and the handler's answer

  Scenario: a service serves its HTTP endpoints and its gRPC endpoints at once
    Given a service "cart" with an HTTP endpoint and a gRPC endpoint for the service definition "CartService"
    When a developer starts the service "cart"
    Then the service "cart" serves the methods of "CartService"
    And the service "cart" serves the routes of the HTTP endpoint

  Scenario: a gRPC endpoint that is not registered with the service is not served
    Given a gRPC endpoint for the service definition "CartService" that is not registered with the service "cart"
    When a developer calls the method "GetCart" of "CartService"
    Then the call ends with the status "unimplemented"

  Scenario: a service whose gRPC endpoint leaves a method without a handler does not start, and says which
    Given a service "cart" with a gRPC endpoint for the service definition "CartService"
    And the gRPC endpoint declares no handler for the method "AddItem"
    When a developer starts the service "cart"
    Then the service "cart" does not start
    And the service "cart" says that the method "AddItem" has no handler

  Scenario: a service with two gRPC endpoints for the same service definition does not start
    Given a service "cart" with two gRPC endpoints for the service definition "CartService"
    When a developer starts the service "cart"
    Then the service "cart" does not start
    And the service "cart" says that "CartService" has two gRPC endpoints

  Scenario: a call to a method no endpoint serves ends as unimplemented
    Given a service "cart" with a gRPC endpoint for the service definition "CartService"
    When a developer calls the method "GetOrder" of "OrderService"
    Then the call ends with the status "unimplemented"
