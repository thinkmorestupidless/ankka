Feature: One request is one trace across services
  A call from one service to another carries its trace context, whether it is made to an HTTP
  endpoint or a gRPC endpoint, and so do a request that arrives from outside the cluster and a
  message published to a topic. A web-hosted service's proxy passes a trace context on as it was
  given. What a service does for the call or the message is recorded under
  the trace of what caused it, so a request that crosses services is one trace in the collector and
  not one for each service.

  Background:
    Given an installation whose telemetry settings name a collector

  Scenario Outline: a request that crosses from one service to another is one trace in the collector
    Given deployed services "orders" and "payments"
    And an endpoint of "orders" that calls <an endpoint> of "payments"
    When "orders" handles 1 request that calls "payments"
    Then the collector holds spans from "orders" and from "payments" with the same trace id
    And the parent of the span of the endpoint of "payments" is the span of the call that "orders" made

    Examples:
      | an endpoint      |
      | an HTTP endpoint |
      | a gRPC endpoint  |

  Scenario Outline: a request that carries no trace context starts a new trace
    Given a deployed service "orders" with <an endpoint>
    When "orders" handles 1 request that carries no trace context
    Then the span of the endpoint is exported with no parent
    And the span has a new trace id

    Examples:
      | an endpoint      |
      | an HTTP endpoint |
      | a gRPC endpoint  |

  Scenario Outline: a request that carries a trace context from outside the cluster continues that trace
    Given a deployed service "orders" with <an endpoint>
    When "orders" handles 1 request that carries the trace context of a span "front-door" from outside the cluster
    Then the span of the endpoint is exported with the trace id of "front-door"
    And the parent of the span of the endpoint is "front-door"

    Examples:
      | an endpoint      |
      | an HTTP endpoint |
      | a gRPC endpoint  |

  Scenario: a message published to a topic continues the trace of what published it
    Given deployed services "orders" and "shipping"
    And a consumer "order-events" of "orders" that publishes to the topic "orders"
    And a consumer "dispatcher" of "shipping" that reads the topic "orders"
    When "order-events" publishes 1 message and "dispatcher" handles it
    Then the collector holds spans from "orders" and from "shipping" with the same trace id
    And the parent of the span of "dispatcher" is the span of "order-events"

  Scenario: a message that carries no trace context starts a new trace
    Given a deployed service "shipping" with a consumer "dispatcher" that reads the topic "orders"
    When "dispatcher" handles 1 message that carries no trace context
    Then the span of "dispatcher" is exported with no parent
    And the span has a new trace id

  Scenario: a request that passes through a web-hosted service keeps its trace context
    Given a deployed web-hosted service "shop-web" with a mount of the service "orders"
    When "shop-web" is sent 1 request under the mount that carries the trace context of a span "front-door"
    Then the span of the endpoint of "orders" is exported with the trace id of "front-door"
    And the parent of the span of the endpoint of "orders" is "front-door"

  Scenario: a call the process of a web-hosted service makes keeps the trace context the process gave it
    Given a deployed web-hosted service "shop-web" and a deployed service "orders"
    When the process of "shop-web" calls "orders" at the calling address with the trace context of a span "page"
    Then the span of the endpoint of "orders" is exported with the trace id of "page"
    And the parent of the span of the endpoint of "orders" is "page"
