@ignore
Feature: How a gRPC call ends
  Every gRPC call ends with a status. A refusal ends the call with the status of its own kind and
  its message, so whoever called can act on it. A failure ends the call with a status that says
  nothing of what went wrong.

  Scenario Outline: a refusal ends the call with its status and its message
    Given a gRPC endpoint whose handler for the method "AddItem" calls a component
    And the component answers with the refusal "<refusal>" and the message "the cart is checked out"
    When a developer calls the method "AddItem"
    Then the call ends with the status "<status>" and the message "the cart is checked out"

    Examples:
      | refusal      | status              |
      | bad request  | invalid argument    |
      | unauthorized | unauthenticated     |
      | forbidden    | permission denied   |
      | not found    | not found           |
      | conflict     | failed precondition |
      | timeout      | deadline exceeded   |
      | unavailable  | unavailable         |
      | internal     | internal            |

  Scenario: a handler that fails ends the call as an internal error that says nothing of the failure
    Given a gRPC endpoint whose handler for the method "AddItem" fails with the message "connection reset"
    When a developer calls the method "AddItem"
    Then the call ends with the status "internal"
    And the call does not end with the message "connection reset"

  Scenario: a request the endpoint cannot read ends the call as an invalid argument, and no handler runs
    Given a gRPC endpoint that declares a handler for the method "AddItem"
    When a developer calls the method "AddItem" with a request the gRPC endpoint cannot read
    Then the call ends with the status "invalid argument"
    And no handler runs
