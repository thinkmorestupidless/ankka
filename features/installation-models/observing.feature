Feature: Observing the model server
  An operator reads the model server as they read the broker: the metrics its keeper sends, in the installation's
  telemetry store, and each call to it as a span in the trace of the turn that made it.

  Scenario: the model server's metrics reach the installation's telemetry store
    Given an installation with a model server and a telemetry store
    When the model server has answered a call
    Then the telemetry store holds the metrics the keeper sends for the model server, among them how many calls it answered, how many are in flight and how many are waiting

  Scenario: a call to an installation model is a span in the turn's trace
    Given a deployed service "support" with an agent "helper" on an installation model
    When a caller sends a message to "helper"
    Then the trace of the turn holds a span for the call to the model server
    And the span names the model provider and the installation model
