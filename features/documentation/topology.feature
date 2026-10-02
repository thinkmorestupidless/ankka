Feature: The documentation of a service's topology
  A reader who takes observed calls to be every call a service can make has been misled, so the
  documentation says what they are wherever it describes the topology.

  Scenario: the documentation of the local console says that observed calls are not every call
    Given the published documentation
    When a reader reads about the local console
    Then the documentation describes the topology
    And the documentation says that observed calls are the calls made in the window, not every call a service can make

  Scenario: the documentation says that the console shows a deployed service's topology
    Given the published documentation
    When a reader reads about what the console shows of a deployed service
    Then the documentation says that the console shows the topology of a deployed service
    And the documentation does not say that nothing inside a deployed service is shown
