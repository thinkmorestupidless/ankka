@ignore
Feature: One service calling another's gRPC endpoint
  A service calls a gRPC endpoint of another service by the other's name, as itself. The call is
  sent only to the service asked for, and a call that cannot be made says why.

  Scenario: a service calls a gRPC endpoint of another service of its project by name
    Given a deployed service "cart" in the project "shop" that serves gRPC
    And a deployed service "checkout" in the project "shop"
    When the service "checkout" calls the method "GetCart" of the service "cart"
    Then the call ends with the status "ok"

  Scenario: a service calls a gRPC endpoint of a service of another project by project and name
    Given a deployed service "cart" in the project "shop" that serves gRPC
    And a deployed service "reports" in the project "finance"
    When the service "reports" calls the method "GetCart" of the service "cart" of the project "shop"
    Then the call ends with the status "ok"
    And the handler for the method "GetCart" reads the calling workload as the service "reports" of the project "finance"

  Scenario: a call is not sent to a workload that is not the service asked for
    Given a deployed service "checkout"
    And a workload at the gRPC address of "cart" whose certificate names the service "orders"
    When the service "checkout" calls the method "GetCart" of the service "cart"
    Then the call fails, and the service "checkout" is shown that the workload is not the service "cart"
    And no request is sent to the workload

  Scenario: a call to a service that cannot be found fails, naming the service
    Given a deployed service "checkout"
    And no service "basket"
    When the service "checkout" calls the method "GetCart" of the service "basket"
    Then the call fails, and the service "checkout" is shown that the service "basket" cannot be found

  Scenario: a call to a service that does not serve gRPC fails, saying so
    Given a deployed service "checkout"
    And a deployed service "orders" whose descriptor does not declare gRPC
    When the service "checkout" calls the method "GetOrder" of the service "orders"
    Then the call fails, and the service "checkout" is shown that the service "orders" does not serve gRPC

  Scenario: a refusal by the called service reaches the calling handler as that refusal
    Given a deployed service "cart" whose handler for the method "GetCart" answers with the refusal "not found"
    And a deployed service "checkout"
    When a handler of the service "checkout" calls the method "GetCart" of the service "cart"
    Then the handler of the service "checkout" is given the refusal "not found"

  Scenario: a service calls a method of another service that takes a stream and answers with a stream
    Given a deployed service "cart" whose handler for the method "Converse" takes a stream and answers each part it reads with one part
    And a deployed service "checkout"
    When the service "checkout" calls the method "Converse" of the service "cart" and sends "1" part without ending the stream
    Then the service "checkout" is given "1" part

  Scenario: on a developer's machine a service calls a gRPC endpoint of another service running there
    Given a service "cart" running on a developer's machine that serves gRPC
    And a service "checkout" running on a developer's machine
    When the service "checkout" calls the method "GetCart" of the service "cart"
    Then the call ends with the status "ok"
