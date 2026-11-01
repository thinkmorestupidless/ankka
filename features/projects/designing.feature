Feature: Designing a project of several services
  A coding agent holding the platform's skills decides whether an application is one service or
  several before it designs any component. For several, it writes the context map first: each
  service, what it owns, why its boundary is where it is, and every crossing. Services that must be
  deployed together are one service, because the platform deploys each service on its own.

  Background:
    Given a coding agent holding the platform's skills

  Scenario: a design of several services starts with a context map that names each service, what it owns and why its boundary is there
    When the coding agent designs "a shop whose ordering and invoicing are owned by different teams"
    Then the design starts with a context map
    And the context map names each service and what it owns
    And the context map says, for each boundary, whether ownership, scaling or consistency put it there

  Scenario: every crossing of a boundary in a context map is a declared topic with its contract or an endpoint route, and names who calls whom
    Given a design of the services "orders" and "billing", where "billing" acts on orders placed and "orders" asks "billing" for an invoice
    When the coding agent writes the context map
    Then the context map has a crossing from "orders" to "billing" that is a declared topic with its contract
    And a crossing from "orders" to "billing" that is a route of an endpoint of "billing", called by "orders"
    And the context map has no other crossing

  Scenario Outline: a crossing that is neither a declared topic with its contract nor a route is named as a defect
    Given a design in which "<reader>" <reads>
    When the coding agent writes the context map
    Then the context map does not record it as a crossing
    And the design names it as a defect to be removed

    Examples:
      | reader  | reads                                     |
      | billing | reads the database of "orders"            |
      | billing | reads the events of an entity of "orders" |
      | billing | reads a topic that has no contract        |

  Scenario: two services that must be released together are designed as one service
    Given a design of the services "orders" and "stock", where an order may only be placed if its items are held in stock at the same moment
    When the coding agent writes the context map
    Then "orders" and "stock" are one service
    And the design says that they were one service because they must be consistent together

  Scenario: a cycle of calls between services is named and one direction becomes a declared topic
    Given a design in which "orders" calls a route of "billing" and "billing" calls a route of "orders"
    When the coding agent writes the context map
    Then the design names the cycle
    And the design asks which of the two calls becomes a declared topic

  Scenario: a design that needs no boundary stays one service and has no context map
    When the coding agent designs "a shop whose parts share one team, one load and one consistency"
    Then the design is of one service
    And the design has no context map
