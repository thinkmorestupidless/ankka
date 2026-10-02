Feature: Who may call a gRPC endpoint
  A gRPC endpoint states an ACL, and a method may state one of its own. A call the access
  rule does not admit ends with a status that says which kind of refusal it was, and its handler
  does not run.

  Scenario: a gRPC endpoint must state who may call it
    Given a service "cart" with a gRPC endpoint that states no ACL
    When a developer starts the service "cart"
    Then the service "cart" does not start

  Scenario: an endpoint that denies all refuses every call, and no handler runs
    Given a gRPC endpoint whose ACL denies all
    When a developer calls the method "GetCart"
    Then the call ends with the status "permission denied"
    And no handler runs

  Scenario: a method's own ACL replaces its endpoint's for that method
    Given a gRPC endpoint whose ACL denies all
    And the method "GetCart" states an ACL that allows all
    When a developer calls the method "GetCart"
    Then the call ends with the status "ok"

  Scenario: a method that states no ACL answers to its endpoint's
    Given a gRPC endpoint whose ACL denies all
    And the method "GetCart" states an ACL that allows all
    When a developer calls the method "AddItem"
    Then the call ends with the status "permission denied"

  Scenario Outline: an authenticator's answer decides the call and which refusal ends it
    Given a gRPC endpoint whose ACL is an authenticator
    And the authenticator answers "<answer>"
    When a developer calls the method "GetCart"
    Then the call ends with the status "<status>"
    And the handler for the method "GetCart" has run "<runs>" times

    Examples:
      | answer          | status            | runs |
      | allow           | ok                | 1    |
      | unauthenticated | unauthenticated   | 0    |
      | forbidden       | permission denied | 0    |
      | unavailable     | unavailable       | 0    |

  Scenario: a handler reads the principal the authenticator established
    Given a gRPC endpoint whose ACL is an authenticator
    And the authenticator establishes the principal "alice"
    When a developer calls the method "GetCart"
    Then the handler for the method "GetCart" reads the principal "alice"

  Scenario: a handler reads the metadata sent with the call
    Given a gRPC endpoint that declares a handler for the method "GetCart"
    When a developer calls the method "GetCart" with the metadata "tenant" set to "acme"
    Then the handler for the method "GetCart" reads the metadata "tenant" as "acme"

  Scenario: an endpoint that admits a named service admits a call from that service
    Given a deployed service "cart" with a gRPC endpoint whose ACL admits only the service "checkout"
    When the service "checkout" calls the method "GetCart" of the service "cart"
    Then the call ends with the status "ok"

  Scenario: an endpoint that admits a named service refuses a call from any other service
    Given a deployed service "cart" with a gRPC endpoint whose ACL admits only the service "checkout"
    When the service "billing" calls the method "GetCart" of the service "cart"
    Then the call ends with the status "permission denied"
    And no handler runs

  Scenario: a closed endpoint refuses a call to a method it does not have as it refuses one it has
    Given a gRPC endpoint for the service definition "CartService" whose ACL denies all
    When a developer calls the method "NoSuchMethod" of "CartService"
    Then the call ends with the status "permission denied"

  Scenario: on a developer's machine every calling workload is the local caller
    Given a service "cart" running on a developer's machine
    And the service "cart" has a gRPC endpoint whose ACL admits only the service "checkout"
    When a developer calls the method "GetCart"
    Then the handler for the method "GetCart" reads the calling workload as the local caller
    And the call ends with the status "ok"
