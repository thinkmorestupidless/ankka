Feature: Creating a replicated entity
  An installation that has never seen an entity creates it as its home on the first persisting
  command. Two creations of one id at two installations before either has heard of the other are
  two homes; when the histories meet the entity is marked in conflict at both, writes are refused
  with an error that says so, reads go on, and an operator resolves it.

  Background:
    Given a service "fleet" with a replicated entity "vehicle"
    And "fleet" runs at the installations "leipzig" and "bratislava"

  Scenario: an entity is created as the home of the installation that first writes it
    When a caller at "bratislava" sends the command "create" to the vehicle "v9" that no installation has seen
    Then "bratislava" is the home of "v9"
    And the replication set of "v9" is "bratislava" alone

  Scenario: two creations of one id at two installations are marked in conflict when they meet
    Given "leipzig" cannot be reached from "bratislava"
    And a caller at "leipzig" sent the command "create" to the vehicle "v9"
    And a caller at "bratislava" sent the command "create" to the vehicle "v9"
    When "leipzig" can be reached from "bratislava" again
    Then "v9" is marked in conflict at "leipzig" and at "bratislava"
    And an operator can see the conflict

  Scenario: an entity in conflict answers reads and refuses writes
    Given the vehicle "v9" is marked in conflict at "leipzig" and at "bratislava"
    When a caller at "leipzig" sends the command "add-step" to "v9"
    And a caller at "leipzig" sends the query "get" to "v9"
    Then the command is refused with an error saying "v9" is in conflict
    And the query is answered with the state "leipzig" holds for "v9"
