Feature: Rebuilding a view that reads entities
  A view that reads entities may declare a version, as a view that reads a topic does. A view
  declared at a higher version than the one recorded for it is rebuilt: emptied, and every one of
  its sources read again from the first thing it recorded. An entity keeps everything it recorded,
  so the rebuilt view holds every row, however old. A view that declares no version is at version 1
  and is never rebuilt. An instance declaring a lower version stops reading for that view.

  Background:
    Given a service "shipping" with an event sourced entity "shipment" and an event sourced entity "customer"
    And a view "shipments" that reads the events of "shipment" and the events of "customer"
    And a view "customers" that reads the events of "customer"

  Scenario: a view of several sources at a higher version is rebuilt from every source
    Given "shipments" at version 1 has read every event of both sources
    When "shipping" restarts with "shipments" at version 2
    Then "shipments" holds no row written at version 1
    And "shipments" has read every event of "shipment" and every event of "customer" again
    And "shipments" holds every row those events are written to at version 2

  Scenario: a view of one source at a higher version is rebuilt
    Given "customers" at version 1 has read every event of "customer"
    When "shipping" restarts with "customers" at version 2
    Then "customers" holds no row written at version 1
    And "customers" holds a row written at version 2 for every entity of "customer"

  Scenario: a view restarted at the same version is not rebuilt
    Given "shipments" at version 1 has read every event of both sources
    When "shipping" restarts with "shipments" at version 1
    Then "shipments" reads no event again
    And every row of "shipments" is as it was

  Scenario: a view that declares no version is never rebuilt
    Given "customers" declaring no version has read every event of "customer"
    When "shipping" restarts with "customers" declaring no version
    Then "customers" reads no event again
    And every row of "customers" is as it was

  Scenario: instances starting together at a higher version rebuild a view that reads entities once
    Given "shipments" at version 1 has read every event of both sources
    When 2 instances of "shipping" start together with "shipments" at version 2
    Then "shipments" is emptied once
    And "shipments" holds only rows written at version 2

  Scenario: during a rolling update the instance at the lower version stops writing a view that reads entities
    Given an instance of "shipping" running "shipments" at version 1 that has read every event of both sources
    When an instance of "shipping" running "shipments" at version 2 starts beside it
    And the entity "s1" of "shipment" records one more event
    Then "shipments" is emptied once
    And the instance at version 1 reads no more events for "shipments"
    And "shipments" holds no row written at version 1

  Scenario: a service rolled back to a lower version leaves a view that reads entities as it is
    Given "shipments" at version 2 has read every event of both sources
    When "shipping" restarts with "shipments" at version 1
    Then every row of "shipments" is as it was
    And "shipments" reads no more events
    And "shipping" is ready
    And the service's log says the view is behind its recorded version
