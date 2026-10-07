Feature: A declared topic carries a contract
  A member declares a topic's contract with the topic. A component states the contract of each
  topic it reads or publishes to, and a service whose component states another one, or none where
  the topic has one, is refused before it reads or publishes a message. A topic declared without a
  contract checks nothing. A published message carries the contract as its type.

  Background:
    Given an installation with a broker
    And a project "money"

  Scenario: a topic is declared with a contract
    When a member declares the topic "transactions" on "money" with 3 partitions and the contract "transaction.v1"
    Then the topics of "money" show "transactions" with the contract "transaction.v1"

  Scenario: a component that states the declared contract is accepted
    Given the topic "transactions" is declared on "money" with the contract "transaction.v1"
    And a service "wallet" whose consumer publishes to "transactions" stating the contract "transaction.v1"
    When a member applies the descriptor for "wallet"
    Then "wallet" is ready

  Scenario: a component that states another contract is refused, naming both
    Given the topic "transactions" is declared on "money" with the contract "transaction.v1"
    And a service "wallet" whose consumer publishes to "transactions" stating the contract "transaction.v2"
    When a member applies the descriptor for "wallet"
    Then the consumer neither reads nor publishes
    And the status of "wallet" says the topic "transactions" is declared "transaction.v1" and the consumer states "transaction.v2"

  Scenario: a component that states no contract on a topic that has one is refused
    Given the topic "transactions" is declared on "money" with the contract "transaction.v1"
    And a service "wallet" whose consumer publishes to "transactions" stating no contract
    When a member applies the descriptor for "wallet"
    Then the consumer neither reads nor publishes
    And the status of "wallet" says the topic "transactions" is declared "transaction.v1" and the consumer states none

  Scenario: a topic without a contract checks nothing
    Given the topic "transactions" is declared on "money" with no contract
    And a service "wallet" whose consumer publishes to "transactions" stating the contract "transaction.v1"
    When a member applies the descriptor for "wallet"
    Then "wallet" is ready

  Scenario: a published message carries the contract as its type
    Given the topic "transactions" is declared on "money" with the contract "transaction.v1"
    And a ready service "wallet" whose consumer publishes to "transactions" stating the contract "transaction.v1"
    When the consumer publishes a message
    Then the message on the topic "transactions" has the type "transaction.v1"

  Scenario: a contract is shown with the topic
    Given the topic "transactions" is declared on "money" with the contract "transaction.v1"
    When a member lists the topics of "money"
    Then the listing shows "transactions" with the contract "transaction.v1"
