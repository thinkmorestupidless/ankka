Feature: The metrics a service exports
  A service exports how many times each handler ran and for how long, counted since the instance
  started. The trace window forgets; an exported metric does not.

  Background:
    Given an installation whose telemetry settings name a collector
    And a deployed service "orders" of the project "shop" with an event sourced entity "cart"

  Scenario: an exported metric counts every run of a handler since the instance started
    When "cart" handles more commands than the trace window of "orders" holds
    Then the metric exported for "cart" counts every one of them

  Scenario: an exported metric names the same service and project as the spans
    When the metrics of "orders" are exported
    Then each metric names the service "orders" and the project "shop"
    And each metric is counted apart for each component and each handler
