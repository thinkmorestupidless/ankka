Feature: Serving a long wait over HTTP
  A wait may outlast a connection: the service ends a connection that is quiet for its idle
  timeout, and the gateway bounds a response through it. The SDK offers the wait as a stream that
  carries a heartbeat while it waits and the end when it comes, so an HTTP endpoint serves a long
  wait as server-sent events on one connection.

  Background:
    Given a service "reports" with a workflow "report" whose steps take longer than the service's idle timeout

  Scenario: a wait served as server-sent events outlasts the service's idle timeout
    Given an HTTP endpoint of "reports" that serves the end of the workflow "r1" of "report" as server-sent events
    When a browser reads the route while "r1" runs
    Then the browser receives a heartbeat while it waits
    And the browser then receives the state "r1" ended with
    And the connection was not cut

  Scenario: a wait served as one whole answer is cut by the service's idle timeout
    Given an HTTP endpoint of "reports" that answers the end of the workflow "r1" of "report" as one whole answer
    When a browser reads the route while "r1" runs
    Then the connection is cut before "r1" ends
