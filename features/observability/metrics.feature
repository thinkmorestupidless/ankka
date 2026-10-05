Feature: A deployed service's metrics
  Each instance of a deployed service publishes its metrics in the form a monitoring system reads:
  how many times each handler ran, by how it ended, and how long it took, over the service's recent
  window. The platform collects nothing and draws nothing; an installation brings its own monitoring.

  Scenario: a deployed service's metrics count each handler's runs and their duration
    Given a deployed service "cart" with an event sourced entity "cart" whose handler "add-item" has run 3 times
    When the metrics of the service are read
    Then the metrics count 3 runs of "add-item" of "cart", by how each ended
    And the metrics show how long the runs of "add-item" took

  Scenario: a service that has served nothing publishes metrics of zero
    Given a deployed service "cart" that has just started and served nothing
    When the metrics of the service are read
    Then the metrics count no run of any handler
    And reading them did not fail
