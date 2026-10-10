Feature: The documentation of view streams
  A developer learns from the documentation how a query is answered as a stream and how a query is
  watched, what a watch promises and what it does not, and that a module reads a view whole.

  Scenario: the documentation of views describes a query answered as a stream and a watched query
    Given the published documentation
    When a reader reads about asking a view for a stream
    Then the documentation describes a query answered as a stream of rows
    And the documentation describes a watched query, in each language that has one

  Scenario: the documentation says a watch is live and not a record
    Given the published documentation
    When a reader reads about a watched query
    Then the documentation says a watch is live and not a record
    And the documentation says how a watch ends
    And the documentation says a reader who must see every change reads the source with a consumer

  Scenario: the documentation says what view streams do not do
    Given the published documentation
    When a reader reads about what ankka does not do
    Then the documentation says a module reads a view whole
    And the documentation says a watch may not give a watcher every change
