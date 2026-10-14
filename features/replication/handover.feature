Feature: Handing over a replicated entity's home
  The home is handed over by a record the home writes in the entity's own journal naming the new
  home, after everything the old home wrote. The old home refuses writes from then on and forwards
  them. The new home becomes effective only once its own journal ends with the handover record,
  and the old home writes that record only after the new home has confirmed it holds everything
  before it. A follower switches its source at the handover record's sequence number and nowhere
  else, so no copy holds a newer home's record before an older home's.

  Background:
    Given a service "fleet" with a replicated entity "vehicle"
    And "fleet" runs at the installations "leipzig", "vienna" and "bratislava"
    And the vehicle "v1" was created at "leipzig" with "vienna" and "bratislava" in its replication set
    And a caller at "leipzig" sent five commands to "v1"

  Scenario: the home is handed over and the new home handles the next write
    When a handler of "v1" hands its home to "vienna"
    And a caller at "vienna" sends the command "record-sale" to "v1"
    Then the command is handled at "vienna"
    And the journal of "v1" at "vienna" holds the handover and then the sale

  Scenario: the old home forwards a write it is sent after the handover
    Given the home of "v1" was handed to "vienna"
    When a caller at "leipzig" sends the command "record-sale" to "v1"
    Then the caller is answered as the handler of "record-sale" at "vienna" replied
    And the journal of "v1" at "leipzig" holds no record written at "leipzig" after the handover

  Scenario: a copy that lagged through a handover holds every change in the home's order
    Given the replication transport to "bratislava" is paused
    And the home of "v1" was handed to "vienna"
    And a caller at "vienna" sent three commands to "v1"
    When the replication transport to "bratislava" is resumed
    Then the journal of "v1" at "bratislava" holds the same records at the same sequence numbers as at "vienna"
    And the five commands from "leipzig" come before the handover and the three from "vienna" after it

  Scenario: a handover waits until the new home holds everything before it
    Given the replication transport to "vienna" is paused
    When a handler of "v1" hands its home to "vienna"
    Then the handover is not recorded while "vienna" is behind
    And the handover is recorded once the replication transport to "vienna" is resumed and "vienna" holds every record before it

  Scenario: a handover to an installation outside the replication set is refused
    Given "bratislava" was removed from the replication set of "v1"
    When a handler of "v1" hands its home to "bratislava"
    Then the handler's command is refused naming "bratislava" as outside the replication set of "v1"

  Scenario: a handover is recorded in the entity's own history
    When a handler of "v1" hands its home to "vienna"
    Then the journal of "v1" holds a handover record naming "vienna" after every record "leipzig" wrote
    And the history of "v1" at every installation in its replication set holds the handover at the same sequence number

  Scenario: the old home goes on handling writes while a handover waits
    Given the replication transport to "vienna" is paused
    And a handler of "v1" handed its home to "vienna"
    When a caller at "leipzig" sends the command "add-step" to "v1"
    Then the command is handled at "leipzig"
    And the handover is recorded after that command once the replication transport to "vienna" is resumed

  Scenario: a handover configured to hold writes is abandoned when the new home does not confirm in time
    Given "fleet" configures a handover to hold writes and to be abandoned after "5 seconds"
    And the replication transport to "vienna" is paused
    When a handler of "v1" hands its home to "vienna"
    And a caller at "leipzig" sends the command "add-step" to "v1"
    Then a caller waiting for the handover is told it did not happen after "5 seconds"
    And "leipzig" is still the home of "v1"
    And the command "add-step" is handled at "leipzig" after the handover is abandoned
