Feature: Asking a view a declared query
  A view declares the queries it can be asked beyond one row and every row. Each declared query
  has a name, a statement the developer writes where the view is declared, and the names of the
  values it takes. The platform checks every statement by the tables it reads when the service
  starts: a statement that is not one query of the view's own table stops the service from
  starting, so the database is never sent it. A handler asks by name and gives the values, which
  are never read as part of the statement.

  Background:
    Given a service "org" with an event sourced entity "node"
    And a view "nodes" that reads the events of "node" and keeps a row for each entity of it

  Scenario: a declared query is asked by name and answered with rows
    Given "nodes" declares the query "of-kind" with a statement that reads its own table for the rows holding the value "kind"
    And "nodes" holds the rows "a" and "b", each holding "team", and the row "c" holding "person"
    When a handler of "org" asks "nodes" the query "of-kind" with "team" as "kind"
    Then the handler is answered with the rows "a" and "b" and no other

  Scenario: a value given to a declared query is never read as part of its statement
    Given "nodes" declares the query "of-kind" with a statement that reads its own table for the rows holding the value "kind"
    And "nodes" holds the rows "a" and "b", each holding "team"
    When a handler of "org" asks "nodes" the query "of-kind" with a statement that deletes every row as "kind"
    Then the handler is answered with no rows
    And "nodes" holds the rows "a" and "b"

  Scenario: a query the view does not declare is refused
    When a handler of "org" asks "nodes" the query "of-size"
    Then the handler is refused
    And the refusal names "nodes" and "of-size"
    And the database is sent nothing

  Scenario Outline: a service whose view declares a statement that is not one query of the view's own table does not start
    Given "nodes" declares the query "broken" with a statement that <does>
    When "org" is started
    Then "org" does not start
    And the developer is told that the query "broken" of "nodes" is refused, and why
    And the database is never sent the statement

    Examples:
      | does                                     |
      | writes a row                             |
      | deletes a row                            |
      | holds a second statement after the query |
      | reads a table that is no view's          |

  Scenario: a statement that reads another view's table is refused, and the refusal names the table
    Given a view "accounts" of "org"
    And "nodes" declares the query "broken" with a statement that reads the table of "accounts"
    When "org" is started
    Then "org" does not start
    And the developer is told that the query "broken" of "nodes" reads the table of "accounts"

  Scenario: a statement is checked by the tables it reads, not by the names written in it
    Given "nodes" declares the query "noted" with a statement that reads its own table and names another table only in a comment and in a value
    When "org" is started
    Then "org" starts
    And a handler of "org" that asks "nodes" the query "noted" is answered with rows

  Scenario: a declared query that is not given a value it takes is refused
    Given "nodes" declares the query "of-kind" with a statement that reads its own table for the rows holding the value "kind"
    When a handler of "org" asks "nodes" the query "of-kind" with no value
    Then the handler is refused
    And the refusal names the value "kind"
