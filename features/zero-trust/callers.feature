Feature: Who sent a request to an HTTP endpoint
  Every request that reaches a deployed service arrives over a proven connection, so its calling
  workload is read from a certificate the platform issued and never from what the request says. An
  HTTP endpoint's ACL can therefore admit the gateway, a named service, or both, and mean it. A
  connection that proves nothing reaches no endpoint.

  Background:
    Given a deployed service "cart" in the project "shop"
    And a deployed service "checkout" in the project "shop"

  Scenario: an HTTP endpoint that admits a named service serves it and tells the handler who called
    Given "cart" has an HTTP endpoint whose ACL admits only the service "checkout"
    When "checkout" sends a request to the route "GET /carts/{cartId}" of "cart"
    Then the handler runs
    And the handler reads the calling workload as the service "checkout" of the project "shop"

  Scenario: an HTTP endpoint refuses a service its ACL does not name, as a refusal and not a failure
    Given "cart" has an HTTP endpoint whose ACL admits only the service "orders"
    When "checkout" sends a request to the route "GET /carts/{cartId}" of "cart"
    Then "checkout" is refused
    And no handler runs
    And the request is recorded as refused and not as failed

  Scenario: an HTTP endpoint that admits the gateway serves a request from the internet
    Given "cart" is exposed
    And "cart" has an HTTP endpoint whose ACL admits only the gateway
    When a person on the internet sends a request to the route "GET /carts/{cartId}" at the hostname of "cart"
    Then the handler runs
    And the handler reads the calling workload as the gateway

  Scenario: an HTTP endpoint that admits only the gateway refuses a service of the installation
    Given "cart" has an HTTP endpoint whose ACL admits only the gateway
    When "checkout" sends a request to the route "GET /carts/{cartId}" of "cart"
    Then "checkout" is refused
    And no handler runs

  Scenario Outline: a connection that does not prove its workload reaches no endpoint
    Given a workload in the cluster
    When the workload connects to "cart" <how>
    Then the connection is refused
    And no handler runs

    Examples:
      | how                                                             |
      | in the clear                                                    |
      | showing no certificate                                          |
      | showing a certificate the authority of the installation did not issue |

  Scenario: a handler behind an authenticator is told both the principal and the calling workload
    Given "cart" has an HTTP endpoint whose ACL is an authenticator
    And the authenticator establishes the principal "alice"
    When "checkout" sends a request to the route "GET /carts/{cartId}" of "cart"
    Then the handler reads the principal "alice"
    And the handler reads the calling workload as the service "checkout" of the project "shop"

  Scenario Outline: a service in any language is told the same calling workload
    Given "cart" is written in "<language>" and has an HTTP endpoint whose ACL admits only the service "checkout"
    When "checkout" sends a request to the route "GET /carts/{cartId}" of "cart"
    Then the handler reads the calling workload as the service "checkout" of the project "shop"
    And a request from the service "orders" to the same route is refused

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |

  Scenario: on a developer's machine every request comes from the local caller, and the service says once that callers are not checked
    Given a service "orders" running on a developer's machine
    And "orders" has an HTTP endpoint whose ACL admits only the service "checkout"
    When a developer sends a request to the route "GET /orders/{orderId}" of "orders"
    Then the handler reads the calling workload as the local caller
    And the log of "orders" says once, as it starts, that callers are not checked outside a cluster
