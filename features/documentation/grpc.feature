Feature: The documentation of gRPC endpoints
  A developer who cannot find how to build a gRPC endpoint has not been given one, and a developer
  who was not told what gRPC does not do will find out from a deployed service.

  Scenario: the documentation describes building and testing a gRPC endpoint
    Given the published documentation
    When a developer reads about gRPC endpoints
    Then the documentation describes how a gRPC endpoint is built from a service definition
    And the documentation describes how a test calls a gRPC endpoint with the test kit
    And the documentation describes how an exposed service's gRPC endpoint is called from outside the cluster
    And the documentation describes how a service opts into reflection, and says that reflection lists the methods of every gRPC endpoint

  Scenario: the documentation says what gRPC does not do
    Given the published documentation
    When a developer reads about what the platform does not do
    Then the documentation says that only an embedded service serves gRPC
    And the documentation says that a web page cannot call a gRPC endpoint
    And the documentation says that the local console does not call a method
    And the documentation does not say that a service serves HTTP only
