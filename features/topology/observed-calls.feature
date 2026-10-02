Feature: Observed calls in a service's topology
  A service's topology shows which of its components called which, as the service saw it happen.
  Each observed call is counted where it was handled (ok, refused or failed) and where it went
  unanswered (timed out or undelivered), and the two counts are never added together. An observed
  call is a call that was made in the window; a call the service can make and has not made is not
  shown.

  Scenario: a call from an endpoint to an entity is attributed to the route and the handler
    Given a service with an event sourced entity "cart"
    And an endpoint whose route "POST /carts/{cartId}/items" calls the command "add-item" of "cart"
    And the service has handled nothing since it started
    When the service handles 1 request to "POST /carts/{cartId}/items"
    Then the topology shows an observed call from the endpoint to "cart"
    And the observed call is from the route "POST /carts/{cartId}/items" to the handler "add-item"
    And the observed call is handled 1 time, as ok

  Scenario: a refusal is counted as refused and not as failed
    Given a service with an event sourced entity "cart" whose command "add-item" refuses a quantity of 0
    And an endpoint that calls the command "add-item" of "cart"
    When the endpoint calls "add-item" with a quantity of 0
    Then the observed call from the endpoint to "cart" is handled 1 time, as refused
    And the observed call is not marked as failing

  Scenario: a handler that fails is counted as failed where it ran and as timed out where it was called
    Given a service with an event sourced entity "cart" whose command "add-item" fails
    And an endpoint that calls the command "add-item" of "cart"
    When the endpoint calls "add-item"
    Then the observed call from the endpoint to "cart" is handled 1 time, as failed
    And the observed call is unanswered 1 time, as timed out
    And the topology shows the handled count and the unanswered count apart

  Scenario: a call its caller stopped waiting for is counted as timed out, and as handled when the handler finishes
    Given a service with an event sourced entity "cart" whose command "add-item" takes longer than its caller waits
    And an endpoint that calls the command "add-item" of "cart"
    When the endpoint calls "add-item"
    Then the observed call from the endpoint to "cart" is unanswered 1 time, as timed out
    And the observed call is handled 1 time, as ok

  Scenario: each call a workflow step makes is attributed to that step
    Given a service with an event sourced entity "cart" and an event sourced entity "stock"
    And a workflow "checkout" whose step "reserve" calls "cart" and "stock"
    When "checkout" runs the step "reserve"
    Then the topology shows an observed call from "checkout" to "cart" from the step "reserve"
    And the topology shows an observed call from "checkout" to "stock" from the step "reserve"

  Scenario: a call made outside any handler comes from the unknown caller
    Given a service with an event sourced entity "cart"
    When "cart" is called from outside any handler
    Then the topology shows an observed call from the unknown caller to "cart"
    And the topology shows no observed call from a component to "cart"

  Scenario: a call that was not made in the window is not shown
    Given a service with an event sourced entity "cart" and an event sourced entity "wallet"
    And an endpoint that calls "cart" for one route and "wallet" for another
    When the service handles 1 request to the route that calls "cart"
    Then the topology shows an observed call from the endpoint to "cart"
    And the topology shows no observed call to "wallet"
    And the topology says that its observed calls are the calls made in the window, not every call the service can make

  Scenario: calls to many entity ids are one observed call, and no entity id is shown
    Given a service with an event sourced entity "cart"
    And an endpoint whose route "POST /carts/{cartId}/items" calls the command "add-item" of "cart"
    When the service handles 100 requests to "POST /carts/{cartId}/items", each for a different entity id
    Then the topology shows 1 observed call from the endpoint to "cart"
    And the observed call is handled 100 times, as ok
    And the topology shows no entity id

  Scenario: a call to a handler the component does not declare is counted as undelivered
    Given a service with an event sourced entity "cart" that declares no handler "add-gift"
    And an endpoint that calls the handler "add-gift" of "cart"
    When the endpoint calls "add-gift"
    Then the observed call from the endpoint to "cart" is unanswered 1 time, as undelivered
    And the observed call is to an undeclared handler
    And the topology shows no handler "add-gift"

  Scenario: a call that reaches no instance is counted as undelivered
    Given a service with an event sourced entity "cart"
    And an endpoint that calls the command "add-item" of "cart"
    And no instance that can handle a call to "cart"
    When the endpoint calls "add-item"
    Then the observed call from the endpoint to "cart" is unanswered 1 time, as undelivered

  Scenario: a stream is counted once, when it ends
    Given a service with an agent "helper" whose handler "ask" answers as a stream
    And an endpoint that calls the handler "ask" of "helper"
    When the endpoint calls "ask" and the stream ends
    Then the observed call from the endpoint to "helper" is handled 1 time, as ok
    And the observed call is marked as a stream

  Scenario: a timer's call is counted when the timed action runs, not when the timer is set
    Given a service with a timed action "reminder" that calls the event sourced entity "cart"
    And a timer set for "reminder"
    When the timer fires
    Then the topology shows an observed call from "reminder" to "cart"
    And the observed call is handled 1 time, as ok

  Scenario: an observed call leaves the topology when its last call leaves the window
    Given a service with an event sourced entity "cart"
    And an endpoint that has called "cart" 1 time
    When the window passes with no other call to "cart"
    Then the topology shows no observed call to "cart"
    And the topology says how long its window is

  Scenario: a restarted service counts its observed calls from the restart
    Given a service with an event sourced entity "cart"
    And an endpoint that has called "cart" 1 time
    When the service restarts
    Then the topology shows no observed call
    And the topology says that its observed calls are counted since the service started

  Scenario: reading an entity through the local console is not a call
    Given a service with an event sourced entity "cart"
    When a developer reads the state of "cart" through the local console
    Then the topology shows no observed call to "cart"
    And the topology shows no unknown caller

  Scenario: the unknown caller is one caller however many calls it made
    Given a service with an event sourced entity "cart" and an event sourced entity "wallet"
    When "cart" and "wallet" are called from outside any handler
    Then the topology shows 1 unknown caller
    And the topology shows an observed call from the unknown caller to "cart"
    And the topology shows an observed call from the unknown caller to "wallet"
