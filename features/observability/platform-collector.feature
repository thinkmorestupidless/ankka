Feature: The collector the platform offers
  An installation's collector is its own. The platform offers one that an installation may add,
  the least there is to send to: it receives telemetry from every project of the installation and
  from nothing else, and keeps none of it. The platform's own services export as any service
  does.

  Scenario: a service of any project reaches the platform's collector
    Given a local platform with the platform's collector added
    And a deployed service "orders" of the project "shop"
    When "orders" handles 1 request
    Then the platform's collector holds the spans of the request

  Scenario: a workload that is not of the installation cannot reach the platform's collector
    Given a local platform with the platform's collector added
    When a workload that is not of the installation sends telemetry to the platform's collector
    Then the platform's collector holds none of it

  Scenario: an installation that is not a local platform names a collector of its own
    Given an installation that is not a local platform
    When the installation is made as the platform publishes it
    Then its telemetry settings name no collector until the installation sets one
    And its telemetry settings never name a local platform's collector
    And the installation has no telemetry store

  Scenario: the control plane exports as a service does
    Given an installation whose telemetry settings name a collector
    When the control plane handles 1 request
    Then the collector holds the spans of the request
    And each span names the control plane
