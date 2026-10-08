Feature: A project asks for replicas and survives losing its primary
  A project database runs a primary alone until the project asks for replicas. With replicas, a
  lost primary is replaced by a promoted replica with no member doing anything, and the project's
  services connect to it on their own. A project that carries money may ask that its project
  database be synchronous, so that no write it acknowledged is lost with the primary. A project
  that asks for nothing keeps a primary alone.

  Scenario: a member asks for replicas and the project database runs them
    Given a project "shop" with a project database
    When a member asks for 2 replicas of the project database of "shop"
    Then the project database of "shop" runs a primary and 2 replicas
    And the status of the project "shop" says how many of them are ready and which is the primary

  Scenario: a lost primary is replaced by a promoted replica and the services go on writing without a redeploy
    Given the project database of "shop" runs a primary and 2 replicas
    And a deployed service "wallet" in the project "shop" that writes to it
    When the primary is lost
    Then a replica is promoted with no member doing anything
    And "wallet" goes on writing with no instance replaced
    And the connections of "wallet" to the lost primary are closed, and "wallet" connects to the promoted replica on its own

  Scenario: a synchronous project database loses no acknowledged write with its primary
    Given the project database of "shop" is synchronous, with a primary and 2 replicas
    And a deployed service "wallet" in the project "shop"
    When a write of "wallet" is acknowledged
    Then a replica holds the write as well as the primary
    And losing the primary loses no write that was acknowledged

  Scenario: a project that asks for no replicas runs a primary alone
    Given a project "shop" that asks for no replicas
    When a member applies a descriptor for the service "cart" in the project "shop"
    Then the project database of "shop" runs a primary and no replica

  Scenario: lowering the number of replicas keeps the primary and everything recorded
    Given the project database of "shop" runs a primary and 2 replicas
    And a deployed service "wallet" in the project "shop" that has recorded an event
    When a member asks for 1 replica of the project database of "shop"
    Then the project database of "shop" runs a primary and 1 replica
    And the primary is the one it had
    And "wallet" still holds the event
