Feature: The installation says where telemetry goes
  Where a service's telemetry is sent is the installation's to say, once, and never the service's.
  Every service deployed to an installation whose telemetry settings name a collector exports to
  it, whatever language it is written in and without its developer asking. A web-hosted service has
  no components to record, and exports nothing. The telemetry settings are platform settings the
  platform alone sets.

  Scenario: a deployed service exports without its descriptor asking
    Given an installation whose telemetry settings name a collector
    And a descriptor for a service "orders" that says nothing of telemetry
    When a member applies the descriptor
    Then each instance of "orders" is given the telemetry settings
    And the collector holds spans from "orders"

  Scenario Outline: a service exports whatever language it is written in, with no change to its code
    Given an installation whose telemetry settings name a collector
    And a deployed service "orders" written in "<language>"
    When "orders" handles 1 request
    Then the collector holds the spans of the request
    And each span names the service "orders"

    Examples:
      | language |
      | Scala    |
      | Python   |
      | Rust     |

  Scenario Outline: a descriptor may not give a telemetry setting
    Given a descriptor for a service "orders" that gives the variable "<variable>"
    When a member applies the descriptor
    Then the member is refused
    And the refusal names the variable "<variable>"

    Examples:
      | variable            |
      | ANKKA_OTLP_ENDPOINT |
      | ANKKA_OTLP_HEADERS  |

  Scenario: a service of an installation that names no collector exports nothing
    Given an installation whose telemetry settings name no collector
    When a member applies a descriptor for a service "orders"
    Then "orders" exports nothing
    And "orders" tries to reach no collector

  Scenario: a web-hosted service exports nothing
    Given an installation whose telemetry settings name a collector
    And a deployed web-hosted service "shop-web"
    When "shop-web" handles 1 request
    Then the collector holds no spans from "shop-web"
    And the process of "shop-web" is not given the telemetry settings
