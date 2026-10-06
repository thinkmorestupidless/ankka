Feature: The telemetry store of a local platform
  A local platform has a telemetry store, so that a developer can read what their services did
  without installing anything else: the trace of a request, the metrics of a service, and the
  logs its instances printed, joined to the traces they belong to. The logs reach it from what the
  instances printed and not from the services, which export none. It is for a developer's
  machine: it keeps nothing when it restarts, and an installation that is not a local platform
  has its own.

  Background:
    Given a local platform with a telemetry store
    And a deployed service "orders"

  Scenario: a developer reads the trace of a request in the telemetry store
    When "orders" handles 1 request
    Then the telemetry store shows the trace of the request
    And each span of the trace names the service "orders"

  Scenario: a developer reads the metrics of a service in the telemetry store
    When "orders" handles 3 requests
    Then the telemetry store shows a metric of "orders" that counts the 3 requests

  Scenario: a developer reads the logs of a service in the telemetry store, joined to their trace
    Given a handler of "orders" that writes to the logs
    When the handler runs
    Then the telemetry store shows what the handler printed in the logs of "orders"
    And what the telemetry store shows names the trace id of the span of the handler
    And the telemetry store shows the trace with that trace id
    And "orders" exported none of its logs
