Feature: A process calls another service through the platform's own program
  A service hosted as a process never holds its certificate. Its process asks the platform's own
  program beside it to call another service, and the platform's own program makes the call as the
  service, with nothing the process says changing who the call came from.

  Background:
    Given a deployed service "psp-gateway" in the project "payments" written in "Python"
    And a deployed service "merchant" in the project "payments"

  Scenario: the process of a service that calls other services holds no certificate
    Given a handler of "psp-gateway" has called "merchant"
    When what the process of "psp-gateway" holds is read
    Then the process holds no certificate

  Scenario: nothing the process says in a call makes it come from another service
    When a handler of "psp-gateway" calls "merchant" saying in the call that it came from the service "orders"
    Then the handler of "merchant" reads the calling workload as the service "psp-gateway" of the project "payments"

  Scenario: two calls a process makes at once are both answered without waiting for each other
    Given a deployed service "slow" in the project "payments" that answers after "5" seconds
    When a handler of "psp-gateway" calls "slow" and another handler of "psp-gateway" calls "merchant" at the same time
    Then the handler that called "merchant" is given its answer before "slow" answers

  Scenario Outline: an entity's handler in a process may not call another service
    Given a handler of the <kind> "account" of "psp-gateway"
    When the handler calls "merchant"
    Then the handler is told that a <kind> may not call another service
    And no call is sent to any service

    Examples:
      | kind                  |
      | event sourced entity  |
      | key value entity      |

  Scenario: a call a process makes outside any handler is made and counted from the unknown caller
    When the process of "psp-gateway" calls "merchant" outside any handler
    Then the handler of "merchant" reads the calling workload as the service "psp-gateway" of the project "payments"
    And the topology of "psp-gateway" counts the call from the unknown caller

  Scenario: a process made for a protocol version before calls to other services is not served one
    Given the descriptor of "psp-gateway" states a protocol version from before calls to other services
    When a handler of "psp-gateway" calls "merchant"
    Then the handler is told that the protocol version it was made for has no call to another service
    And no call is sent to any service
