Feature: What a watch promises and what it does not
  A row written on any instance reaches a watcher on any instance. A watcher is never given an
  older version of a row after a newer one, and a row that changes faster than the watcher reads
  reaches it fewer times than it changed. A watch ends, with the reason, when the view is emptied
  for a rebuild or the instance serving it stops. Open watches on an instance are bounded by a
  platform setting, and a watch beyond the bound is refused naming it. A watch is one call in the
  topology.

  Background:
    Given a service "shop" with an event sourced entity "cart"
    And a view "carts" that reads the events of "cart" and keeps a row for each entity of it
    And "carts" declares the query "open-carts" with a statement that reads its own table for the rows whose cart is open

  Scenario: a row written on another instance reaches a watcher
    Given two instances of "shop"
    And a handler on the first instance watching "open-carts"
    When the second instance writes the row "c4" for "carts"
    Then the handler is given the row "c4"

  Scenario: a watcher that reads slowly is given the last version of a row and fewer than were written
    Given a handler of "shop" watching "open-carts" that does not read
    When "carts" writes the row "c1" "50" times
    And the handler then reads
    Then the handler is given the last version of "c1"
    And the handler is given fewer than "50" versions of "c1"

  Scenario: a watcher with more unread rows than its unread bound loses the oldest unless it chose otherwise
    Given a handler of "shop" watching "open-carts" with an unread bound of "10" rows that does not read
    When "carts" writes "15" rows for different carts
    And the handler then reads
    Then the handler is given the "10" rows written last
    And the handler is not given the "5" rows written first until they are written again

  Scenario: a watcher that chose to fail on overflow is told its watch ended unread
    Given a handler of "shop" watching "open-carts" with an unread bound of "10" rows and the overflow strategy "fail" that does not read
    When "carts" writes "11" rows for different carts
    Then the watch ends
    And the watcher is told the watch ended unread

  Scenario: every watch of a view ends when the view is emptied for a rebuild
    Given handlers of "shop" watching "open-carts"
    When "shop" restarts with "carts" at a higher version
    And "carts" is emptied
    Then every watch of "carts" ends
    And each watcher is told the view was rebuilt

  Scenario: a watch ends when the instance serving it stops
    Given two instances of "shop"
    And a handler watching "open-carts" served by the first instance
    When the first instance stops
    Then the watch ends
    And the watcher is told the instance stopped

  Scenario: a watch beyond the instance's bound is refused naming the bound
    Given an instance of "shop" with as many open watches as the installation's watch bound
    When a handler of "shop" watches "open-carts"
    Then the handler is refused
    And the refusal names the watch bound

  Scenario: a watch is one observed call in the topology
    Given a handler of "shop" that watched "open-carts" and stopped
    When a developer reads the service's topology
    Then the topology shows one observed call from the handler to "carts", handled as ok

  Scenario: a watch is evaluated when a row is written
    Given "carts" declares the query "due-carts" with a statement whose match depends on the time
    And a handler of "shop" watching "due-carts"
    When the row "c1" comes to match "due-carts" by the passing of time alone
    Then the handler is given nothing for "c1" until "carts" writes the row "c1" again

  Scenario: a watch of a declared query whose results are not the view's rows is refused at start
    Given "carts" declares the query "cart-count" with a statement that counts its rows
    And "carts" declares the query "newest-carts" with a statement that reads its own table for the newest "10" rows
    And a handler of "shop" that watches "cart-count" and a handler that watches "newest-carts"
    When "shop" is started
    Then "shop" does not start
    And the developer is told that a watched query gives the view's rows and has no limit
    And "cart-count" and "newest-carts" can still be asked whole
