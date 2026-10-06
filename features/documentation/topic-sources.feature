Feature: What the documentation says of topic sources
  A rebuild from a topic reaches back only as far as the broker retains, and a group's name carries
  the service's. The documentation says both where a reader building a view will meet them, rather
  than leaving either to be found by the first rebuild that comes back short.

  Scenario: the documentation describes groups, start positions and versions, and what bounds a rebuild
    When a reader looks up topic sources in the documentation
    Then the documentation describes how a group is named
    And the documentation describes start positions and versions, each with its default
    And the documentation says a rebuild reaches back only as far as the broker retains

  Scenario: the documentation's limitations say what bounds a topic source's rebuild
    When a reader looks up topic sources in the limitations of the documentation
    Then the documentation says a rebuild is bounded by what the broker retains
    And the documentation says a rebuild of a view reading an entity is not bounded by what a broker retains

  Scenario: the documentation says what an upgrade does to a service's groups
    When a reader looks up upgrading in the documentation
    Then the documentation says the name of a group changed
    And the documentation says an upgraded view reads again from its start position
    And the documentation says a consumer reading a topic must declare its start position
