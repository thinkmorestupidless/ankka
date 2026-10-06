Feature: What the platform made on the broker is kept
  The platform never removes a topic, what was published to it, or a service's credential from the
  installation's broker: not when a service is deleted, not when a topic is no longer declared, and
  not when the project is deleted. A service deployed again under the same name finds its credential
  and its project's topics as it left them. Whoever runs the installation removes a topic that is no
  longer wanted.

  Background:
    Given an installation with a broker
    And the topic "transactions" is declared on "money"
    And a deployed service "wallet" in the project "money"
    And a consumer of "wallet" has published to the topic "transactions"

  Scenario: a deleted service's topic keeps what was published to it
    When a member deletes "wallet"
    Then the installation's broker still has the topic "transactions" of "money"
    And what was published to it is still read from it

  Scenario: a service deployed again finds its credential and its topic
    Given "wallet" has since been deleted
    When a member applies the descriptor for "wallet" again
    Then the status says that the broker of "wallet" is "Recovered"
    And what was published before "wallet" was deleted is still read from the topic "transactions"

  Scenario: a view of a service deployed again reads on from where it had read to
    Given a view "entries" of "wallet" that reads the topic "transactions" and shows what was published
    And "wallet" has since been deleted
    When a member applies the descriptor for "wallet" again
    Then the view "entries" shows what was published once and not twice

  Scenario: a topic no longer declared keeps what was published to it
    When a member removes the declaration of the topic "transactions" from "money"
    Then the installation's broker still has the topic "transactions" of "money"
    And what was published to it is still read from it

  Scenario: a topic declared again finds what was published to it
    Given the declaration of the topic "transactions" has since been removed from "money"
    When a member declares the topic "transactions" on "money"
    Then the topic "transactions" is "Recovered"
    And what was published to it is still read from it

  Scenario: a deleted project's topics are kept
    Given "wallet" has since been deleted
    When a member deletes the project "money"
    Then the installation's broker still has the topic "transactions" of "money"
