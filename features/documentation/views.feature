Feature: The documentation of views of several sources
  A developer learns from the documentation how a view names the rows it writes, what moving a row
  asks of the view, how a recursive query is asked, and which sources may not share a view.

  Scenario: the documentation of views describes the row key a view names
    Given the published documentation
    When a reader reads about the rows of a view
    Then the documentation describes a view of several sources and the row key it names for each row
    And the documentation says that a view of one source that names no row key keeps each row under the entity id it came from

  Scenario: the documentation says that a moved row is deleted and written by the view
    Given the published documentation
    When a reader reads about moving a row of a view
    Then the documentation says that the platform deletes no row a view did not name
    And the documentation says that a view moves a row by deleting it under its old row key and writing it under its new one

  Scenario: the documentation of views describes declared queries and the recursive query
    Given the published documentation
    When a reader reads about the queries of a view
    Then the documentation describes how a view declares a query and how a handler asks it
    And the documentation describes the recursive query
    And the documentation says which statements stop a service from starting

  Scenario: the documentation says how a view that reads entities is rebuilt
    Given the published documentation
    When a reader reads about rebuilding a view
    Then the documentation says that declaring a higher version starts a rebuild of a view that reads entities
    And the documentation says that a view of several sources handles one event at a time

  Scenario: the documentation says that a topic and an entity may not share a view
    Given the published documentation
    When a reader reads about what a view does not do
    Then the documentation says that a topic and an entity may not be sources of one view
