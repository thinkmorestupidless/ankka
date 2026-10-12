Feature: Watching a view's query
  A declared query can be watched, and so can one row by its row key. A watcher is first given
  every row the query matches now, then each row that comes to match or is written again while
  it matches, and a removal naming the row key when a row it was given stops matching or is
  deleted. A watch is live and not a record: it shows what the view writes while the watcher is
  reading, and ends when the watcher stops reading. A reader who must see every change reads the
  source with a consumer.

  Background:
    Given a service "shop" with an event sourced entity "cart"
    And a view "carts" that reads the events of "cart" and keeps a row for each entity of it
    And "carts" declares the query "open-carts" with a statement that reads its own table for the rows whose cart is open

  Scenario: a watcher is first given every row the query matches now
    Given "carts" holds the open carts "c1" and "c2" and the cart "c0" that is checked out
    When a handler of "shop" watches "open-carts"
    Then the handler is first given the rows "c1" and "c2" and no other

  Scenario: a watcher is told it is caught up after the rows now and before any change
    Given "carts" holds the open carts "c1" and "c2"
    When a handler of "shop" watches "open-carts" and the cart "c3" is then opened
    Then the handler is given the rows "c1" and "c2"
    And the handler is then told it is caught up
    And the handler is then given the row "c3"

  Scenario: a watcher of a query that matches nothing now is told it is caught up at once
    Given "carts" holds no open cart
    When a handler of "shop" watches "open-carts"
    Then the handler is told it is caught up before any row

  Scenario: a watcher is given a row that comes to match
    Given a handler of "shop" watching "open-carts"
    When the cart "c3" is opened and "carts" writes the row "c3"
    Then the handler is given the row "c3"

  Scenario: a watcher is given a row it has that is written again
    Given a handler of "shop" watching "open-carts" that was given the row "c1"
    When an item is added to the cart "c1" and "carts" writes the row "c1" again
    Then the handler is given the row "c1" as it was written again

  Scenario: a watcher is given a removal for a row it has that stops matching
    Given a handler of "shop" watching "open-carts" that was given the row "c2"
    When the cart "c2" is checked out and "carts" writes the row "c2" so that it no longer matches "open-carts"
    Then the handler is given a removal naming "c2"

  Scenario: no removal is given for a row the watcher never had
    Given a handler of "shop" watching "open-carts" that was not given the row "c0"
    When "carts" writes the row "c0" so that it still does not match "open-carts"
    Then the handler is given nothing for "c0"

  Scenario: a watch ends when the watcher stops reading
    Given a handler of "shop" watching "open-carts"
    When the handler stops reading
    Then the watch ends
    And no further row is produced for it

  Scenario: what was written while nobody watched is not given to a later watcher
    Given "carts" wrote the row "c5" while no handler was watching "open-carts"
    And the cart "c5" was then checked out
    When a handler of "shop" watches "open-carts"
    Then the handler is not given the row "c5"

  Scenario: an HTTP endpoint serves a watch as server-sent events
    Given an HTTP endpoint of "shop" that serves "open-carts" as server-sent events
    And "carts" holds the open carts "c1" and "c2"
    When a browser reads the route and the cart "c3" is then opened
    Then the browser receives the rows "c1" and "c2" and then the row "c3" as one stream

  Scenario: one row watched by its row key is given now and then each version written
    Given "carts" holds the row "c1"
    And a handler of "shop" watching the row "c1"
    When "carts" writes the row "c1" three times
    Then the handler was first given the row "c1" as it stood
    And the handler is given the versions in the order they were written
    And the handler is never given an older version after a newer one

  Scenario: a watched row that is deleted is given as a removal
    Given a handler of "shop" watching the row "c1"
    When "carts" deletes the row "c1"
    Then the handler is given a removal naming "c1"

  Scenario: a watched row that does not exist yet is given when it is written
    Given "carts" holds no row "c9"
    And a handler of "shop" watching the row "c9" that was told it is caught up at once
    When "carts" writes the row "c9"
    Then the handler is given the row "c9"
