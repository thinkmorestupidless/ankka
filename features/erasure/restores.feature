Feature: A restored backup cannot bring an erased data subject back
  Subject keys are kept in the keyring, outside every project's database and backup, so a
  project's database restored to before an erasure holds only what cannot be read. The keyring's
  own backup would hold a destroyed subject key, so every erasure is written to the erasure log,
  kept in two places outside the keyring's database, before any subject key is destroyed, and the
  keyring applies the erasure log before it answers anyone after a restore. A service restored
  with its project applies the erasure log to its own tables before it is ready.

  Background:
    Given a project "brand" with the service "players"
    And "player/8c1f" has been erased in "brand"

  Scenario: a service restored to before an erasure is not ready until it has applied the erasure log to its own tables
    Given the keyring has not been restored
    When the database of "brand" is restored to a point before the erasure
    And "players" is switched to the restored database
    Then "players" is not ready until it has applied the entries of the erasure log for "brand" to its own tables
    And afterwards every row of every view of "players" holds erased in each personal field of "player/8c1f"
    And no lookup token for "player/8c1f" remains in any row of "players"
    And the status of the restore says when "players" finished applying the erasure log

  Scenario: an entity recovered from a restored database reads an erased data subject as erased, because the keyring was not restored
    Given the keyring has not been restored
    And the database of "brand" has been restored to a point before the erasure
    When an entity of "players" holding events of "player/8c1f" is recovered
    Then its state holds erased in each personal field of "player/8c1f"

  Scenario: the keyring restored to before an erasure applies the erasure log before it answers any request
    When the database of the keyring is restored to a point before the erasure
    Then the keyring answers no request for a subject key until it has applied the erasure log
    And afterwards the keyring holds no subject key for "player/8c1f"
    And the next read of a personal field of "player/8c1f" by any service of "brand" is erased

  Scenario: the keyring applies the union of both copies of the erasure log and reports the copy that was behind
    Given one copy of the erasure log is behind the other
    When the keyring applies the erasure log
    Then it applies every entry that either copy holds
    And it reports which copy was behind

  Scenario: an erasure whose erasure log write failed destroys no subject key
    Given an erasure request for "player/9d2e" in "brand" whose write to the erasure log fails
    When the erasure request is applied
    Then no subject key is destroyed
    And the erasure request is not applied, and says why
