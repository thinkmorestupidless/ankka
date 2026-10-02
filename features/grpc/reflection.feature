@ignore
Feature: Asking a service what it serves
  A service that opts into reflection tells a tool its service definitions and their methods.
  Reflection has an ACL of its own, and a service that has not opted in answers none.

  Scenario: a service that has not opted into reflection answers none
    Given a service "cart" with a gRPC endpoint for the service definition "CartService"
    And the service "cart" has not opted into reflection
    When a developer asks the service "cart" for reflection
    Then the call ends with the status "unimplemented"

  Scenario: a service that opts into reflection tells a tool its service definitions and their methods
    Given a service "cart" with a gRPC endpoint for the service definition "CartService"
    And the service "cart" opts into reflection with an ACL that allows all
    When a developer asks the service "cart" for reflection
    Then the developer is told the service definition "CartService"
    And the developer is told the methods of "CartService"

  Scenario: a service that opts into reflection must state who may ask
    Given a service "cart" that opts into reflection and states no ACL for reflection
    When a developer starts the service "cart"
    Then the service "cart" does not start

  Scenario: a tool that reflection's ACL does not admit is refused and told nothing
    Given a deployed service "cart" that opts into reflection with an ACL that admits only the service "checkout"
    When the service "billing" asks the service "cart" for reflection
    Then the call ends with the status "permission denied"
    And the service "billing" is told nothing of what the service "cart" serves

  Scenario: reflection lists a method whose endpoint denies all, and the method still refuses every call
    Given a service "cart" with a gRPC endpoint for the service definition "CartService" whose ACL denies all
    And the service "cart" opts into reflection with an ACL that allows all
    When a developer asks the service "cart" for reflection and then calls the method "GetCart" of "CartService"
    Then the developer is told the methods of "CartService"
    And the call ends with the status "permission denied"
