Feature: The test kit and replicated entities
  The test kit starts several installations of one service on separate databases through the
  runtime's own replication transport, pauses and resumes the transport between any two, and
  makes a home unreachable. The entity test kit drives one entity as its home or as a follower.

  Scenario: the test kit starts several installations of one service and replicates between them
    Given a test that starts the service "fleet" at the installations "a", "b" and "c" through the test kit
    When the test creates the vehicle "v1" at "a", hands its home to "b" and reads it at "c"
    Then the test observes the same history of "v1" at "a", "b" and "c"

  Scenario: the entity test kit drives an entity as its home or as a follower
    Given a test driving the entity "vehicle" in the entity test kit as a follower
    When the test sends a persisting command
    Then the command is refused as not at the home
    And a query is answered from the state the test gave the follower
