Feature: Asking a view a recursive query
  A view whose rows each hold the row key of another row of the same view, as a node holds its
  parent's, can be asked for everything under one row in one recursive query, which the database
  follows to any depth. A recursive query is a declared query like any other: one statement, of
  the view's own table, checked when the service starts.

  Background:
    Given a service "org" with an event sourced entity "node"
    And a view "nodes" that reads the events of "node" and keeps a row for each entity of it
    And each row of "nodes" holds the row key of the row it is under, or none
    And "nodes" declares the recursive query "under" that takes the value "row" and reads every row under it

  Scenario: a recursive query answers with every row under a row, to any depth, and no other
    Given "nodes" holds these rows
      | row key | under |
      | a       |       |
      | b       | a     |
      | c       | b     |
      | d       | b     |
      | e       |       |
      | f       | e     |
    When a handler of "org" asks "nodes" the query "under" with "a" as "row"
    Then the handler is answered with the rows "b", "c" and "d" and no other
    And the database was sent one statement

  Scenario: a recursive query over a thousand rows is answered within the time a query is given
    Given "nodes" holds "1000" rows, the row "root" and "999" rows under it, "10" deep
    When a handler of "org" asks "nodes" the query "under" with "root" as "row"
    Then the handler is answered with "999" rows
    And the answer arrives within the time a query of a view is given

  Scenario: a recursive query that never ends is stopped when the time a query is given runs out
    Given "nodes" declares a recursive query "around" that never ends
    When a handler of "org" asks "nodes" the query "around"
    Then the handler is told that the time a query of a view is given ran out
    And the database is no longer running the statement
