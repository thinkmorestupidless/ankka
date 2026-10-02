@ignore
Feature: gRPC calls in a service's traces
  A gRPC call is recorded as an HTTP request is: the root of the trace of what it caused, marked
  by how it ended. A refusal is the service working, so it is not recorded as failed.

  Scenario: a gRPC call is the root of the trace of everything it caused
    Given a gRPC endpoint for the service definition "CartService" whose handler for the method "GetCart" calls a component
    When a developer calls the method "GetCart" of "CartService"
    Then the service records a trace whose root is the call to the method "GetCart" of "CartService"
    And the trace shows the call to the component under the root

  Scenario: a refused gRPC call is recorded as refused, not as failed
    Given a gRPC endpoint whose handler for the method "GetCart" answers with the refusal "not found"
    When a developer calls the method "GetCart"
    Then the service records a trace whose root is marked refused

  Scenario: a failed gRPC call is recorded as failed
    Given a gRPC endpoint whose handler for the method "GetCart" fails with the message "connection reset"
    When a developer calls the method "GetCart"
    Then the service records a trace whose root is marked failed

  Scenario: a call answered with a stream is one call in the trace
    Given a gRPC endpoint whose handler for the method "WatchCart" answers with a stream that produces "3" parts
    When a developer calls the method "WatchCart"
    Then the service records a trace with "1" root
