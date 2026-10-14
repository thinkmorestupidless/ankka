Feature: A replicated entity's replication set
  The replication set is the installations that hold a replicated entity: its home and its
  followers. A newly created entity's replication set is its home alone. A persisting handler adds
  an installation or removes one, composed with what it persists; both are records in the entity's
  own journal. An added installation mirrors from the first record; a removed one stops mirroring
  and keeps its copy.

  Background:
    Given a service "fleet" with a replicated entity "vehicle"
    And "fleet" runs at the installations "leipzig", "vienna" and "bratislava"
    And the vehicle "v1" was created at "leipzig"
    And a caller at "leipzig" sent twelve commands to "v1"

  Scenario: a newly created entity is held by its home alone
    Then the replication set of "v1" is "leipzig" alone
    And neither "vienna" nor "bratislava" holds a record of "v1"

  Scenario: an installation added to the replication set mirrors the whole history
    When a handler of "v1" adds "vienna" to its replication set
    Then "vienna" holds every record of "v1" from the first

  Scenario: an installation added late holds the same history as the home
    Given "vienna" was added to the replication set of "v1" after the twelve commands
    And a caller at "leipzig" sent three more commands to "v1"
    When "vienna" has caught up
    Then the journal of "v1" at "vienna" holds the same records at the same sequence numbers as at "leipzig"

  Scenario: an installation removed from the replication set stops mirroring and keeps its copy
    Given "vienna" is in the replication set of "v1" and holds every record
    When a handler of "v1" removes "vienna" from its replication set
    And a caller at "leipzig" sends the command "add-step" to "v1"
    Then the journal of "v1" at "vienna" does not grow
    And "vienna" keeps the records of "v1" it held

  Scenario: a change to the replication set is recorded in the entity's own history
    Given "vienna" is in the replication set of "v1" and holds every record
    When a handler of "v1" removes "vienna" from its replication set
    Then the journal of "v1" holds a record naming "vienna" as removed from the replication set
    And the record is at "leipzig" and at "vienna" at the same sequence number, the last record "vienna" holds

  Scenario: a read at an installation removed from the replication set is refused naming the home
    Given "vienna" was removed from the replication set of "v1"
    When a caller at "vienna" sends the query "get" to "v1"
    Then the caller is refused with an error naming "leipzig" as the home of "v1"
