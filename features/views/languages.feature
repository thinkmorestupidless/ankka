Feature: Views of several sources and declared queries in every language
  A view of several sources is declared, and a query is declared and asked, the same way whether
  the service is written in Scala, Python, TypeScript or Rust, because the platform's own program
  reads the sources, keeps the table and checks every statement, and the developer's program says
  only which rows to write and what to ask.

  Scenario Outline: a recursive query answers with the same rows in every language
    Given a service "org" written in "<language>" with a view "nodes" whose rows each hold the row key of the row they are under
    And "nodes" declares the recursive query "under" that takes the value "row" and reads every row under it
    And "nodes" holds the rows "a", "b" and "c", each under the one before it
    When a handler of "org" asks "nodes" the query "under" with "a" as "row"
    Then the handler is answered with the rows "b" and "c" and no other

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a service whose view declares a statement that reads another table does not start in every language
    Given a service "org" written in "<language>" with a view "nodes" and a view "accounts"
    And "nodes" declares the query "broken" with a statement that reads the table of "accounts"
    When "org" is started
    Then "org" does not start
    And the developer is told that the query "broken" of "nodes" reads the table of "accounts"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a view of several sources reads every one of them in every language
    Given a service "shipping" written in "<language>" with a view "shipments" that reads the events of "shipment" and the events of "customer"
    When the entity "s1" of "shipment" and the entity "c1" of "customer" each record an event that "shipments" writes the row "s1" for
    Then "shipments" holds one row "s1"
    And the row "s1" holds what was written for both events

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a view finds the rows an event is about by asking a query of its own in every language
    Given a service "shipping" written in "<language>" with a view "shipments" that reads the events of "shipment" and the events of "customer"
    And "shipments" declares the query "of-customer" with a statement that reads its own table for the rows holding the value "customer"
    And "shipments" holds the rows "s1" and "s2", each holding "c1", and the row "s3" holding "c2"
    When the entity "c1" of "customer" records an event for which "shipments" asks its own query "of-customer" with "c1" as "customer" and writes each row it is answered with
    Then the rows "s1" and "s2" hold what was written for that event
    And the row "s3" is as it was

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: the topology shows a view connected to each of its sources in every language
    Given a service "shipping" written in "<language>" with a view "shipments" that reads the events of "shipment" and the events of "customer"
    When a developer reads the service's topology
    Then the topology shows a declared connection from "shipment" to "shipments" as an event subscription
    And the topology shows a declared connection from "customer" to "shipments" as an event subscription

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |
