Feature: Views and consumers at every installation
  Views and consumers at every installation read their own journal and see the home's order. A
  consumer that publishes to a topic, and a graph consumer, act only on local changes unless the
  consumer declares that it reacts to mirrored changes too. A change tells its handler whether it
  is local or mirrored, and which installation wrote it.

  Background:
    Given a service "fleet" with a replicated entity "vehicle", a view "floor" of its changes and a consumer "builds" that publishes each of its changes to the topic "vehicle-builds"
    And "fleet" runs at the installations "leipzig" and "bratislava"
    And the vehicle "v1" was created at "leipzig" with "bratislava" in its replication set

  Scenario: a view at a follower shows a change written at the home
    When a caller at "leipzig" sends the command "add-step" to "v1"
    Then the view "floor" at "bratislava" holds the step within "2 seconds" of the view "floor" at "leipzig"

  Scenario: a consumer that publishes to a topic publishes each change once across installations
    When a caller at "leipzig" sends three commands to "v1"
    Then the topic "vehicle-builds" holds three messages
    And each message was published from "leipzig"

  Scenario: a consumer that asks for mirrored changes is given them
    Given the consumer "builds" declares that it reacts to mirrored changes
    When a caller at "leipzig" sends the command "add-step" to "v1"
    Then the consumer "builds" at "bratislava" is given the change as a mirrored change

  Scenario: a change tells its handler whether it is local or mirrored
    Given the consumer "builds" does not declare that it reacts to mirrored changes
    When a caller at "leipzig" sends the command "add-step" to "v1"
    Then the consumer "builds" at "leipzig" is given the change as a local change written at "leipzig"
    And the consumer "builds" at "bratislava" is not given the change
