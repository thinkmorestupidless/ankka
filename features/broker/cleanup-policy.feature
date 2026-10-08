Feature: A compacted topic of a project's own
  A topic's declaration says its cleanup policy: "delete", which removes a message by the topic's
  retention time and size; "compact", which keeps the last message under each key; or
  "compact,delete", which does both. A project makes a compacted topic by declaring one, with no
  tool outside the platform. A compacted topic refuses a message published under no key, so a
  service refuses such a message before it is sent.

  Background:
    Given an installation with a broker
    And a project "casino"

  Scenario: a topic declared with the cleanup policy "compact" is compacted
    When a member declares the topic "deltas" on "casino" with the cleanup policy "compact"
    Then the topic "deltas" of "casino" is compacted on the installation's broker
    And the status of "deltas" shows the cleanup policy "compact"

  Scenario: a topic with the cleanup policy "compact,delete" keeps the last message under a key only within its retention time
    Given the topic "deltas" is declared on "casino" with the cleanup policy "compact,delete" and the retention time "30 days"
    And the last message under the key "node:c1" was published "31 days" ago
    And the last message under the key "node:c2" was published "1 day" ago
    When the installation's broker compacts "deltas"
    Then no message under the key "node:c1" is read from "deltas"
    And the last message under the key "node:c2" is read from "deltas"

  Scenario Outline: a message published under no key to a compacted topic fails in the service before it reaches the broker
    Given the topic "deltas" is declared on "casino" with the cleanup policy "<policy>"
    And a deployed service "lobby" in "casino"
    And a consumer "sink" of "lobby" that publishes to the topic "deltas" under no key
    When "sink" handles a change
    Then publishing fails in "lobby" before anything is sent to the installation's broker
    And the error names the topic "deltas" and that a compacted topic needs a key
    And "sink" is handed the change again

    Examples:
      | policy         |
      | compact        |
      | compact,delete |

  Scenario: a deletion on a compacted topic is read for the topic's tombstone window
    Given the topic "deltas" is declared on "casino" with the cleanup policy "compact" and the tombstone window "1 day"
    When a consumer of "casino" publishes under the key "node:c1" that it is deleted
    Then the deletion is read from "deltas" for at least "1 day"

  Scenario: no message on a compacted topic is compacted away within its minimum compaction lag
    Given the topic "deltas" is declared on "casino" with the cleanup policy "compact" and the minimum compaction lag "1 hour"
    When a consumer of "casino" publishes twice under the key "node:c1" within "1 hour"
    Then both messages are read from "deltas" until "1 hour" has passed since the first
