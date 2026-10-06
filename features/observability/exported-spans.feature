Feature: What an exported span says
  A span is exported as it was recorded: where it ran, which handler it was, how the handler
  ended and what it was nested under. A refusal is exported as a refusal, and a span whose caller
  the service cannot tell is exported as exactly that, never under a parent that was guessed.

  Background:
    Given an installation whose telemetry settings name a collector

  Scenario: an exported span says where it ran, which handler ran and how it ended
    Given a deployed service "orders" of the project "shop" with an event sourced entity "cart"
    When "cart" handles the command "add-item" and its span is exported
    Then the span names the service "orders" and the project "shop"
    And the span names the component "cart" and the handler "add-item"
    And the span is exported as ok

  Scenario: a refusal is exported as refused and never as failed
    Given a deployed service "orders" with an event sourced entity "cart"
    When "cart" answers the command "add-item" with a refusal and its span is exported
    Then the span is exported as refused
    And the span is not exported as failed

  Scenario: a span with an unknown caller is exported with no parent and is never given one
    Given a deployed service "orders" with a handler that makes a call the service cannot attribute to it
    When the span of that call is exported
    Then the span is exported with no parent
    And the span is marked as having an unknown caller
