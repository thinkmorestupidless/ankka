Feature: A service's logs say which trace they belong to
  What a service prints while a handler runs names the trace id and the span of that handler,
  which is what joins a trace in the collector to the logs the installation gathers. A service's
  logs are never exported: they are read where they were printed, whether or not a collector can
  be reached.

  Background:
    Given a service "orders"

  Scenario: what a handler prints names the trace id and the span of the handler
    Given a handler of "orders" that writes to the logs
    When the handler runs
    Then what the handler printed in the logs of "orders" names the trace id of the span of the handler
    And what the handler printed names that span

  Scenario: what a service prints outside any handler names no trace id
    When "orders" writes to the logs outside any handler
    Then what was printed in the logs of "orders" names no trace id

  Scenario: a service's logs are not exported
    Given an installation whose telemetry settings name a collector
    And "orders" is deployed to the installation
    When "orders" writes to the logs
    Then a member who reads the logs of "orders" is shown what was written
    And the collector holds nothing of the logs of "orders"
