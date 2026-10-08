Feature: How many copies a topic has
  The platform backs up no topic, so a topic's copies are the broker's only durability. A topic's
  declaration says how many copies the broker keeps of it and how many of them must hold a message
  before the broker acknowledges it, and the topic keeps both for its life. An installation can be
  installed with a broker of three broker nodes. The control plane bounds the copies by what the
  installation declares; only the operator knows how many broker nodes there are, and it reports a
  topic that asks for more.

  Scenario: an installation installed with three broker nodes sets every setting that counts copies for three
    Given the platform as it is installed in a cluster with three broker nodes
    When the installation starts
    Then the broker's default copies, its default minimum in-sync copies and the copies of the broker's own topics are set for three broker nodes
    And the operator reports that the broker has three broker nodes

  Scenario: a topic with more copies than the broker has broker nodes is reported failed by the operator
    Given an installation whose broker has three broker nodes
    And the installation's most copies is 5
    And a project "money"
    When a member declares the topic "transactions" on "money" with 5 copies
    Then "money" declares the topic "transactions"
    And the topic "transactions" is "Failed", naming the broker's three broker nodes
    And nothing is made on the installation's broker

  Scenario: stopping one broker node of three loses no acknowledged message
    Given an installation whose broker has three broker nodes
    And the topic "transactions" is declared on "money" with partitions alone
    And a consumer of "money" has published 100 messages to "transactions", each acknowledged
    When one broker node is stopped
    And the consumer publishes 100 messages more
    Then 200 messages are read from "transactions" from the earliest

  Scenario Outline: a publication waits for the topic's minimum in-sync copies
    Given an installation whose broker has three broker nodes
    And the topic "transactions" is declared on "money" with 3 copies and <minimum> minimum in-sync copies
    And a deployed service "wallet" in "money" with a consumer that publishes to the topic "transactions"
    When one broker node is stopped
    Then <outcome>

    Examples:
      | minimum | outcome                                                                                                                           |
      | 3       | publishing to "transactions" is refused until the broker node returns, and the log of "wallet" names the topic and the reason     |
      | 2       | what the consumer publishes is read from "transactions"                                                                           |

  Scenario: a topic on a broker of one broker node says it has a single copy
    Given an installation whose broker has one broker node
    And the topic "transactions" is declared on "money"
    When a member reads the topics of "money"
    Then the topic "transactions" says that it has a single copy

  Scenario Outline: a topic's copies and minimum in-sync copies are fixed when it is declared
    Given an installation whose broker has three broker nodes
    And the topic "transactions" is declared on "money" with 3 copies and 2 minimum in-sync copies
    When a member declares the topic "transactions" on "money" with <copies> copies and <minimum> minimum in-sync copies
    Then the member is refused
    And the refusal names that copies and minimum in-sync copies are fixed when a topic is declared
    And nothing else in the declaration is applied

    Examples:
      | copies | minimum |
      | 1      | 1       |
      | 3      | 3       |
