Feature: View streams in every language that can read one
  A Python or TypeScript service asks a declared query as a stream and watches one, reading the
  rows one at a time; a runtime from before view streams refuses a program that asks for one,
  naming the protocol version it needs. A module answers every call whole, so a module that
  declares anything that would stream a view is refused when it is started, and reads a view
  whole.

  Scenario Outline: every row is given as a stream in every language that can read one
    Given a service "shop" written in "<language>" with a view "orders" holding "5000" rows
    When a handler of "shop" asks "orders" for every row as a stream
    Then the handler is given "5000" rows

    Examples:
      | language   |
      | Python     |
      | TypeScript |

  Scenario Outline: a watcher is given the rows now and then a row that comes to match in every language that can read one
    Given a service "shop" written in "<language>" with a view "carts" that declares the query "open-carts"
    And "carts" holds the open carts "c1" and "c2"
    When a handler of "shop" watches "open-carts" and the cart "c3" is then opened
    Then the handler is first given the rows "c1" and "c2"
    And the handler is then given the row "c3"

    Examples:
      | language   |
      | Python     |
      | TypeScript |

  Scenario: a runtime from before view streams refuses a program that asks for one
    Given a service "shop" written in "Python" whose view "carts" declares the query "open-carts" as one that can be watched
    When "shop" is started beside a runtime at a protocol version before view streams
    Then "shop" does not start
    And the developer is told which protocol version a view stream needs

  Scenario: a module that would stream a view is refused when it is started
    Given a module "shop" whose view "carts" declares the query "open-carts" as one that can be watched
    When "shop" is started
    Then "shop" does not start
    And the developer is told that a module reads a view whole
