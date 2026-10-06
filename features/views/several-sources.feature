Feature: A view of several sources
  A view may read several sources, each the events of an event sourced entity or the state of a
  key value entity, into one table. Each source is read in order and on its own. For an event or
  a state it reads, the view names by row key every row it writes and every row it deletes, so
  one event can write several rows and several sources can write one row. The platform deletes
  no row the view did not name. A view of one source that names no row key is as it always was:
  each row is kept under the entity id it came from.

  Background:
    Given a service "shipping" with an event sourced entity "shipment" and an event sourced entity "customer"
    And a view "shipments" that reads the events of "shipment" and the events of "customer"

  Scenario: two sources write one row
    Given the entity "s1" of "shipment" has recorded an event that "shipments" writes the row "s1" for
    When the entity "c1" of "customer" records an event that "shipments" writes the row "s1" for
    Then "shipments" holds one row "s1"
    And the row "s1" holds what was written for both events

  Scenario: a view of several sources holds one row for each row key it was given
    Given the entities "s1", "s2" and "s3" of "shipment" have each recorded an event that "shipments" writes a row under their entity id for
    When the entity "c1" of "customer" records an event that "shipments" writes the rows "s1" and "s2" for
    Then "shipments" holds the rows "s1", "s2" and "s3" and no other

  Scenario: one event writes every row the view names for it and no other
    Given "shipments" holds the rows "s1", "s2" and "s3"
    When the entity "c1" of "customer" records an event that "shipments" writes the rows "s1" and "s2" for
    Then the rows "s1" and "s2" hold what was written for that event
    And the row "s3" is as it was

  Scenario: a view finds the rows an event is about by asking a query of its own
    Given "shipments" declares the query "of-customer" with a statement that reads its own table for the rows holding the value "customer"
    And "shipments" holds the rows "s1" and "s2", each holding "c1", and the row "s3" holding "c2"
    When the entity "c1" of "customer" records an event for which "shipments" asks its own query "of-customer" with "c1" as "customer" and writes each row it is answered with
    Then the rows "s1" and "s2" hold what was written for that event
    And the row "s3" is as it was

  Scenario: a view reads a row of its own table by row key while it handles an event
    Given "shipments" holds the row "s1"
    When the entity "c1" of "customer" records an event for which "shipments" reads the row "s1" and writes it again with what the event says added
    Then the row "s1" holds what it held before and what was written for that event

  Scenario: a view of several sources handles one event at a time
    Given two instances of "shipping"
    When the entity "s1" of "shipment" and the entity "c1" of "customer" each record an event at the same time
    Then "shipments" handles one of the two events, with everything it reads and writes for it, before it handles the other

  Scenario: two sources writing one row at the same time lose neither write
    Given two instances of "shipping"
    And "shipments" holds the row "s1"
    When the entity "s1" of "shipment" and the entity "c1" of "customer" each record an event at the same time for which "shipments" reads the row "s1" and writes it again with what the event says added
    Then the row "s1" holds what was written for both events

  Scenario: the rows one event names are written together or not at all
    Given "shipments" holds the row "s1"
    When the entity "c1" of "customer" records an event for which "shipments" writes the row "s1" and a row that cannot be written
    Then the row "s1" is as it was
    And "shipments" handles that event again

  Scenario: a row is moved by deleting it under its old row key and writing it under its new one
    Given "shipments" holds the row "s1"
    When the entity "s1" of "shipment" records an event that "shipments" deletes the row "s1" and writes the row "s9" for
    Then "shipments" holds the row "s9"
    And "shipments" holds no row "s1"

  Scenario: the platform deletes no row the view did not name
    Given "shipments" holds the row "s1"
    When the entity "s1" of "shipment" records an event that "shipments" writes the row "s9" for and deletes no row for
    Then "shipments" holds the row "s1" and the row "s9"

  Scenario: a restarted view goes on reading each source from where it had reached
    Given "shipments" has read every event of "shipment" and every event of "customer"
    And "shipping" has since restarted
    When the entity "s1" of "shipment" and the entity "c1" of "customer" each record one more event
    Then "shipments" reads those two events
    And "shipments" reads no event it had read before the restart

  Scenario: a view may not read a topic and an entity together
    Given "shipping" has a view "orders" that reads the topic "orders" and the events of "customer"
    When "shipping" is started
    Then "shipping" does not start
    And the developer is told that a topic and an entity may not be sources of one view

  Scenario: a view of one source that names no row key keeps each row under the entity id it came from
    Given "shipping" has a view "customers" that reads the events of "customer" and names no row key
    When the entity "c1" of "customer" records an event that "customers" writes a row for
    Then "customers" holds the row "c1"
