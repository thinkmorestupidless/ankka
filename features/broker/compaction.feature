Feature: A declared topic may be compacted
  A member declares a topic compacted with its partitions, and the platform makes it so on the
  installation's broker, whether the topic is new or already made. Nothing but a declaration makes
  a topic, so a delta topic is declared compacted by the project that owns it.

  Background:
    Given an installation with a broker
    And a project "shop"

  Scenario: a topic declared compacted is made compacted
    When a member declares the topic "cart-deltas" on "shop" with 3 partitions, compacted
    Then the installation's broker holds the topic "cart-deltas" of "shop" compacted
    And the status of the topic "cart-deltas" on "shop" is "Provisioned"

  Scenario: a topic already made is compacted when its declaration says so
    Given the topic "cart-deltas" is declared on "shop" with 3 partitions, not compacted
    When a member declares the topic "cart-deltas" on "shop" with 3 partitions, compacted
    Then the installation's broker holds the topic "cart-deltas" of "shop" compacted
    And what was published to it is still read from it

  Scenario: a compacted topic keeps the last message under each key
    Given the topic "cart-deltas" is declared on "shop" compacted
    And three messages have been published under the key "node:cart-1"
    When the broker has compacted the topic
    Then reading "cart-deltas" from its start gives the last message under "node:cart-1" and not the earlier two

  Scenario: the topics of a project show which are compacted
    Given the topic "cart-deltas" is declared on "shop" compacted
    And the topic "orders" is declared on "shop" not compacted
    When a member lists the topics of "shop"
    Then the listing shows "cart-deltas" compacted and "orders" not
