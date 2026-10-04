Feature: Declaring topics in a descriptor
  A descriptor says one thing about its broker: it declares topics for the platform to make, or it
  names a broker of its own, never both. What it declares is checked when it is applied, before
  anything is made on the broker.

  Background:
    Given an installation with a broker
    And a project "money"

  Scenario Outline: a descriptor's topics are refused when they cannot be made
    Given a descriptor for a service "wallet" in "money" that <declares>
    When a member applies the descriptor
    Then the member is refused
    And the refusal names <named>
    And nothing is made on the installation's broker

    Examples:
      | declares                                                                   | named                      |
      | declares a topic and gives the variable "ANKKA_KAFKA_BOOTSTRAP_SERVERS"    | the topic and the variable |
      | is for a web-hosted service and declares a topic                           | the topic                  |
      | declares the topic "not a topic name"                                      | the topic                  |
      | declares a topic with 0 partitions                                         | the partitions             |

  Scenario: two services of a project that declare one topic agree on its partitions
    Given a deployed service "wallet" in "money" that declares the topic "transactions" with 12 partitions
    And a descriptor for a service "ledger" in "money" that declares the topic "transactions" with 6 partitions
    When a member applies the descriptor
    Then the member is refused
    And the refusal names the topic and "wallet"

  Scenario Outline: a topic's partitions can be made more and never fewer
    Given a deployed service "wallet" in "money" that declares the topic "transactions" with 12 partitions
    When a member applies the descriptor of "wallet" with <partitions> partitions for "transactions"
    Then <outcome>

    Examples:
      | partitions | outcome                                                              |
      | 24         | the installation's broker has the topic "transactions" of "money" with 24 partitions |
      | 6          | the member is refused, and the refusal names the partitions          |
