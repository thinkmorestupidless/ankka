Feature: Declaring a project's topics
  A topic belongs to a project, so it is declared on the project, once, with the partitions it has.
  The platform makes it on the installation's broker for the project, and every service of the
  project publishes to it and reads it by its name. A declaration is checked when a member makes
  it, before anything is made on the broker.

  Background:
    Given an installation with a broker
    And a project "money"

  Scenario: a declared topic is made on the installation's broker for its project
    When a member declares the topic "transactions" on "money" with 12 partitions
    Then the installation's broker has the topic "transactions" of "money" with 12 partitions
    And the broker holds that topic under the name "money.transactions"

  Scenario Outline: a topic is refused when it cannot be made
    When a member declares the topic <topic> on "money" with <partitions> partitions
    Then the member is refused
    And the refusal names <named>
    And nothing is made on the installation's broker

    Examples:
      | topic              | partitions | named          |
      | "not a topic name" | 12         | the topic      |
      | "transactions"     | 0          | the partitions |
      | "transactions"     | 1001       | the partitions |

  Scenario: a topic declared again is still one declaration
    Given the topic "transactions" is declared on "money" with 12 partitions
    When a member declares the topic "transactions" on "money" with 12 partitions
    Then "money" declares the topic "transactions" once, with 12 partitions

  Scenario Outline: a topic's partitions can be made more and never fewer
    Given the topic "transactions" is declared on "money" with 12 partitions
    When a member declares the topic "transactions" on "money" with <partitions> partitions
    Then <outcome>

    Examples:
      | partitions | outcome                                                                              |
      | 24         | the installation's broker has the topic "transactions" of "money" with 24 partitions |
      | 6          | the member is refused, and the refusal names the partitions                          |

  Scenario: a declaration is made on a project of the member's organization only
    Given a project "casino" of another organization
    When a member declares the topic "transactions" on "casino" with 12 partitions
    Then the member is refused
    And nothing is made on the installation's broker

  Scenario Outline: a project's topics say how far the platform has got with them
    Given the topic "transactions" is declared on "money" with 12 partitions
    And the installation's broker <state>
    When a member reads the topics of "money"
    Then the topic "transactions" is "<word>"

    Examples:
      | state                                            | word        |
      | has not yet made the topic                       | Waiting     |
      | has made the topic with fewer partitions so far  | Waiting     |
      | has made the topic                               | Provisioned |
      | has a problem with the topic that will not clear | Failed      |
