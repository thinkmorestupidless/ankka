Feature: A collector that cannot be reached
  Exporting never costs a service its work. While the collector cannot be reached the service
  handles its requests as before and says so once, not once for every span; when the collector
  can be reached again the service exports what its trace window still holds and counts what it
  lost. An instance that stops exports what it holds, for no longer than a limit.

  Background:
    Given an installation whose telemetry settings name a collector
    And a deployed service "orders"

  Scenario: a service whose collector cannot be reached handles its requests as before
    Given the collector cannot be reached
    When "orders" handles 1000 requests
    Then every request is answered
    And the trace of each request is recorded in the trace window of "orders"
    And "orders" takes no longer over the requests than the limit the platform states for recording

  Scenario: a collector that cannot be reached is reported once and not for every span
    Given the collector cannot be reached
    When "orders" tries 10 times to export
    Then the logs of "orders" report once that the collector cannot be reached
    And "orders" waits longer before each new try to reach the collector

  Scenario: a service exports again when its collector can be reached again
    Given the collector could not be reached while "orders" handled 1000 requests
    When the collector can be reached again
    Then the collector holds the spans still in the trace window of "orders"
    And a metric of "orders" counts its lost spans

  Scenario: an instance that stops exports what it holds and is not kept from stopping
    Given an instance of "orders" holding spans it has not yet exported
    When the instance stops
    Then the collector holds those spans
    And exporting keeps the instance from stopping for no longer than the limit the platform states
