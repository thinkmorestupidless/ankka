Feature: The documentation of replicated entities
  A developer learns from the documentation what a replicated entity's home, followers and
  replication set are, how the home is handed over, what a read at a follower may be behind by,
  what replication across installations needs, and what is not offered.

  Scenario: the documentation describes the home, followers, the replication set and the handover
    Given the published documentation
    When a reader reads about replicated entities
    Then the documentation describes the home, the followers and the replication set
    And the documentation describes handing over the home and changing the replication set
    And the documentation says a read at a follower may be behind the home

  Scenario: the documentation says what replication across installations needs and what is not offered
    Given the published documentation
    When a reader reads what ankka does not do
    Then the documentation says what one installation offers for a replicated entity
    And the documentation says the replication transport between installations is "ankka-replication"
    And the documentation says there is no forced takeover and no coordinated creation

  Scenario: the documentation shows how a copy stands read in every language
    Given the published documentation
    When a reader reads about how a copy stands
    Then the documentation shows the read in Scala, Java, Kotlin, Python, TypeScript, Rust and Go, each from a sample a test runs
    And the documentation names no language that cannot read how a copy stands
