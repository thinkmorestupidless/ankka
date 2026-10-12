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
