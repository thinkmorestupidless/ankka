@ignore
Feature: Testing a gRPC endpoint
  A test starts a whole service with the test kit and calls its gRPC endpoints as any other caller
  would, so what the test proves is what a deployed service does.

  Scenario: a test calls a gRPC endpoint of a whole service through the test kit
    Given a test that starts the service "cart" with the test kit
    And the service "cart" has a gRPC endpoint whose handler for the method "GetCart" calls a component
    When the test calls the method "GetCart"
    Then the call ends with the status "ok"
    And the call is answered by the component of the service "cart"
