Feature: Reading the trace of a request in the local console
  The local console shows the trace of each recent request a service handled: every component that
  ran for it, nested as they called each other, each with its own duration, and the time none of
  them accounts for.

  Scenario: the trace of a request shows every component in the order they ran, with the time no component accounts for
    Given a service with an endpoint, an event sourced entity "cart" and a view "carts"
    And the service has handled a request that called "cart" and asked "carts"
    When the developer reads the trace of the request in the local console
    Then the trace shows the endpoint, "cart" and "carts" in the order they ran, each with its own duration
    And the trace shows the time none of them accounts for as unattributed

  Scenario Outline: the trace of a request whose handler did not succeed shows how it ended and which component it was
    Given a service with an endpoint that calls the event sourced entity "cart"
    And the service has handled a request in which the handler of "cart" <ended>
    When the developer reads the trace of the request in the local console
    Then the trace shows "cart" as <shown>

    Examples:
      | ended                      | shown   |
      | answered with a refusal    | refused |
      | failed                     | failed  |
