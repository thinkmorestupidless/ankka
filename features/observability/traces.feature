Feature: The trace of a request
  The trace of a request shows every component that ran a handler for it, whatever kind of
  component it is.

  Scenario: a workflow step is in the trace of the request that ran it
    Given a service with an event sourced entity "cart"
    And a workflow "checkout" whose step "reserve" calls "cart"
    And an endpoint that starts "checkout"
    When the service handles 1 request that runs the step "reserve"
    Then the trace of the request shows the step "reserve" of "checkout"
    And the trace shows the call to "cart" inside the step "reserve"

  Scenario: an agent is in the trace of the request it answered
    Given a service with an agent "helper"
    And an endpoint that calls "helper"
    When the service handles 1 request that "helper" answers
    Then the trace of the request shows "helper"

  Scenario: a consumer is in the trace of the event it handled
    Given a service with an event sourced entity "cart"
    And a consumer "checkout-recorder" that reads the events of "cart"
    When "checkout-recorder" handles 1 event
    Then the trace of the event shows "checkout-recorder"
