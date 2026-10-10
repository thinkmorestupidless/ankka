Feature: A view's query answered as a stream of rows
  A declared query, every row and, in Scala, a condition can be asked for as a stream: each row
  reaches the handler as the database yields it, in the statement's order, with no row held back
  for the rest and no limit unless the handler gives one. A stream runs under the same statement
  timeout a whole answer does, and one the database ends early ends in a failure, never as a
  shorter answer. A stream is produced no faster than it is read.

  Background:
    Given a service "shop" with an event sourced entity "order"
    And a view "orders" that reads the events of "order" and keeps a row for each entity of it

  Scenario: every row is given as a stream, past the limit of a whole answer
    Given "orders" holds "5000" rows
    When a handler of "shop" asks "orders" for every row as a stream
    Then the handler is given "5000" rows
    And each row reaches the handler as the database yields it

  Scenario: a declared query answered as a stream gives every matching row in the statement's order
    Given "orders" declares the query "by-customer" with a statement that reads its own table for the rows holding the value "customer", in an order
    And "orders" holds "3000" rows holding "alice" and "2000" holding "bob"
    When a handler of "shop" asks "orders" the query "by-customer" as a stream with "alice" as "customer"
    Then the handler is given "3000" rows in the statement's order and no other

  Scenario: a whole answer keeps its limit
    Given "orders" holds "5000" rows
    When a handler of "shop" asks "orders" for every row whole
    Then the handler is given "1000" rows

  Scenario: a stream the database ends early ends in a failure and not as a shorter answer
    Given "orders" holds "5000" rows
    And the statement timeout is shorter than reading them takes
    When a handler of "shop" asks "orders" for every row as a stream and reads it
    Then the stream ends in a failure that says the statement was ended
    And the handler is not given a shorter answer

  Scenario: a stream is produced no faster than it is read
    Given "orders" holds "100000" rows
    When a handler of "shop" asks "orders" for every row as a stream and reads "10" rows
    Then fewer than "1000" rows have been produced

  Scenario: a stream stops being produced when the handler goes away
    Given "orders" holds "100000" rows
    When a handler of "shop" asks "orders" for every row as a stream and goes away after "10" rows
    Then the stream stops being produced
