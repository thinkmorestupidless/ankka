Feature: gRPC methods in the local console
  The local console lists what a service on a developer's machine serves. It lists gRPC methods
  beside HTTP routes, and offers a way to call a route only.

  Scenario: the local console lists a service's gRPC methods and does not offer to call them
    Given a service "cart" running on a developer's machine with a gRPC endpoint for the service definition "CartService"
    When a developer reads the service "cart" in the local console
    Then the local console lists the methods of "CartService"
    And the local console does not offer to call a method
