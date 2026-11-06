Feature: An erasure at the home reaches every installation of a replicated entity
  A replicated entity's personal fields are encrypted under subject keys that only its home's keyring
  keeps. A service at a follower is given them by the home's keyring, as a service of the home is,
  and is told when one is destroyed, so one erasure at the home reaches every installation of the
  entity. No other keyring keeps them.

  Background:
    Given an entity of "players" whose home is the installation "north" and whose follower is the installation "south"
    And its events hold the field "email" as a personal field of the data subject "player/8c1f"

  Scenario: a follower of a replicated entity reads a personal field with the subject key the home's keyring gives it
    When the entity is recovered at "south"
    Then it reads the field "email" as it was written
    And the keyring of "south" keeps no subject key of "player/8c1f"

  Scenario: an erasure at the home reaches a follower of a replicated entity within a minute
    When a member asks for an erasure request for "player/8c1f" at "north" and it is applied
    Then within 60 seconds the entity at "south", recovered again, reads the field "email" as erased

  Scenario: a follower that cannot reach the home's keyring and has no subject key retries the recovery of the entity
    Given the keyring of "north" does not answer "south"
    And "south" has no subject key of "player/8c1f"
    When the entity is recovered at "south"
    Then the recovery of the entity fails and is retried
    And the field "email" is never read as erased
