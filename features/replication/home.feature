Feature: A replicated entity's home
  A replicated entity has one home at a time, the installation that handles its writes. Every
  other installation in its replication set is a follower: it mirrors the home's journal in order
  and answers reads from its own copy, which may be behind the home. A replicated entity on one
  installation behaves as an entity did before.

  Background:
    Given a service "fleet" with a replicated entity "vehicle"
    And "fleet" runs at the installations "leipzig" and "bratislava"

  Scenario: a persisting command at the home is handled and recorded as today
    Given the vehicle "v1" was created at "leipzig"
    When a caller at "leipzig" sends the command "add-step" to "v1"
    Then the caller is answered as the handler of "add-step" replied
    And the journal of "v1" at "leipzig" holds the event the handler persisted as its next record

  Scenario: a follower holds the same history in the same order as the home
    Given the vehicle "v1" was created at "leipzig" with "bratislava" in its replication set
    When a caller at "leipzig" sends five commands to "v1"
    Then the journal of "v1" at "bratislava" holds the same records at the same sequence numbers as at "leipzig"

  Scenario: a read at a follower is answered from its own copy
    Given the vehicle "v1" was created at "leipzig" with "bratislava" in its replication set
    And "bratislava" holds every record of "v1"
    When a caller at "bratislava" sends the query "get" to "v1"
    Then the caller is answered with the state of "v1"
    And no call was made to "leipzig"

  Scenario: a replicated entity on one installation behaves as it did before
    Given "fleet" runs at the installation "leipzig" alone
    When a caller at "leipzig" sends commands and queries to the vehicle "v2"
    Then each is answered as it would be for an entity that is not replicated
    And the journal of "v2" holds the same bytes an entity that is not replicated would write
