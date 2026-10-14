Feature: An existing entity made replicated
  An entity that has run on one installation can be declared replicated with nothing to migrate:
  its history is kept, the installation it lives on is its home, and a record written before
  replication is read as written at the home.

  Scenario: an existing entity made replicated keeps its history and its installation as its home
    Given a service "fleet" with an entity "order" that is not replicated, running at the installation "a"
    And the order "o1" has a history of twelve records at "a"
    When "order" is declared replicated and "fleet" names the installations "a" and "b"
    And a handler of "o1" adds "b" to its replication set
    Then "a" is the home of "o1"
    And the history of "o1" at "a" is as it was
    And "b" holds the same twelve records and the change to the replication set after them

  Scenario: a record written before this feature is read as written at the home
    Given a service "fleet" whose entity "order" was declared replicated after the order "o1" had a history at the installation "a"
    When a consumer at "a" is given a change from a record written before "order" was replicated
    Then the change is a local change written at "a"
