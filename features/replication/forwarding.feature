Feature: A write that reaches a follower is handled by the home
  A persisting command that reaches a follower, or an installation outside the replication set,
  is forwarded to the home through the replication transport and its reply returned to the
  caller. When the home cannot be reached the write is refused with an error naming the home, and
  reads at the follower go on. A forwarded command is one call in the trace.

  Background:
    Given a service "fleet" with a replicated entity "vehicle"
    And "fleet" runs at the installations "leipzig", "bratislava" and "vienna"
    And the vehicle "v1" was created at "leipzig" with "bratislava" in its replication set

  Scenario: a persisting command at a follower is answered with the home's reply
    When a caller at "bratislava" sends the command "record-inspection" to "v1"
    Then the caller is answered as the handler of "record-inspection" at "leipzig" replied
    And the journal of "v1" at "leipzig" holds the inspection as its next record

  Scenario: a persisting command at an installation outside the replication set is forwarded to the home
    When a caller at "vienna" sends the command "record-inspection" to "v1"
    Then the caller is answered as the handler of "record-inspection" at "leipzig" replied
    And "vienna" holds no record of "v1"

  Scenario: a write at a follower whose home cannot be reached is refused naming the home
    Given "leipzig" cannot be reached from "bratislava"
    When a caller at "bratislava" sends the command "record-inspection" to "v1"
    Then the caller is refused with an error naming "leipzig" as the home of "v1"

  Scenario: a read at a follower whose home cannot be reached is still answered
    Given "leipzig" cannot be reached from "bratislava"
    When a caller at "bratislava" sends the query "get" to "v1"
    Then the caller is answered with the state "bratislava" holds for "v1"

  Scenario: a forwarded command is one call in the trace
    Given a caller at "bratislava" sent the command "record-inspection" to "v1" and was answered
    When a developer reads the trace of the call
    Then the trace shows one call from the caller to "vehicle", forwarded from "bratislava" to "leipzig"
