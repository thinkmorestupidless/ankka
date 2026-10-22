Feature: The replication transport
  The runtime defines the replication transport, what carries records and forwarded commands
  between installations, and ships one implementation of its own, enough for the test kit and for
  two services on one machine. A service naming several installations with no transport able to
  reach them refuses to start naming what is missing. Only an event sourced entity may be declared
  replicated.

  Scenario: two services on one machine replicate through the runtime's own transport
    Given a service "fleet" with a replicated entity "vehicle" started twice on one machine as the installations "a" and "b", each with its own database
    When a caller at "a" creates the vehicle "v1" with "b" in its replication set and sends it a command
    Then the journal of "v1" at "b" holds the same records as at "a" within "2 seconds"

  Scenario: a service naming several installations without a transport refuses to start, naming what is missing
    Given a service "fleet" with a replicated entity "vehicle" that names the installations "a" and "b"
    And no replication transport can reach "b"
    When "fleet" starts
    Then "fleet" refuses to start
    And the reason names the replication transport as missing

  Scenario Outline: a key value entity or a workflow declared replicated is refused when the descriptor is validated
    Given a service whose descriptor declares a <component> replicated
    When the descriptor is validated
    Then the descriptor is refused naming the <component>

    Examples:
      | component        |
      | key value entity |
      | workflow         |
      | agent            |
      | timer            |

  Scenario: a transport beside the runtime's own is given the replicated components it serves
    Given a service "fleet" with a replicated entity "vehicle" and an entity "depot" that is not replicated
    And a transport beside the runtime's own
    When "fleet" starts
    Then the transport is given "vehicle" as the replicated components and not "depot"

  Scenario: a transport beside the runtime's own delivers a handover's report to the local copy
    Given a service "fleet" running at "leipzig" and "bratislava" over a transport beside the runtime's own
    And the vehicle "v1" homed at "leipzig" with "bratislava" in its replication set
    When the home of "v1" is handed over to "bratislava"
    Then the transport delivers the handover's report to the copy at "leipzig" through the runtime's public operation
    And the next write to "v1" is handled at "bratislava"

  Scenario: a service names an installation it reaches through a transport beside the runtime's own by its name alone
    Given a service "fleet" started with the environment naming the installations "leipzig" and "bratislava" and no database for them
    And a transport beside the runtime's own
    When "fleet" starts
    Then "fleet" names "leipzig" and "bratislava" as installations it may replicate with
