Feature: What a restore cannot take back
  A restore takes a project's journals and read positions back to the restore point. The broker is
  not taken back: messages published from the lost events are still on the topics, and the project's
  groups have read past what the restored journals hold. The restore lists each topic and each group
  that may disagree with it, and does nothing to the broker. A message published from a journal
  event carries a message id made from its line of history, so that an event published again after
  a restore carries the message id it carried before, and an event recorded after the restore never
  shares one with an event that was lost. The message id protects only a reader that deduplicates by
  it: every view and consumer of the platform that reads the topic reads a message published again
  as a new one.

  Background:
    Given a project "shop" that is backed up, with the declared topic "transactions"
    And a deployed service "ledger" in the project "shop" with an entity "account" and a consumer that publishes its events to the topic "transactions"
    And a deployed service "totals" in the project "shop" with a view "sums" that reads the topic "transactions"
    And an owner of the organization "shop" is in

  Scenario: a completed restore lists the topics and the groups that are newer than the restore point
    Given a completed restore of "shop"
    And the topic "transactions" holds messages "ledger" published after the restore point
    And the group of "sums" has read "transactions" past the restore point
    When the owner reads the restore
    Then the restore lists the topic "transactions" with the number of messages newer than the restore point on each partition
    And the restore lists the group of "sums" as having read further than the restore point

  Scenario: an event published again after a restore carries the message id it carried before
    Given "ledger" published the event 3 of the entity "a1" to "transactions" before a restore of "shop"
    And "ledger" switched to the restore
    When "ledger" publishes the event 3 of the entity "a1" again from its restored read position
    Then the message carries the message id the first message carried

  Scenario: an event recorded after a restore carries a message id no message published before it carried
    Given "ledger" switched to a restore of "shop" made before the event 6 of the entity "a1" was recorded
    When "ledger" records a new event 6 of the entity "a1" and publishes it
    Then the message carries a message id that no message published before the restore carried

  Scenario Outline: a restore and a switch do nothing to the broker but list it
    Given a restore of "shop"
    When <action>
    Then the platform publishes nothing to the broker
    And the platform deletes nothing from the broker
    And the platform moves no group's read position
    And the listing of topics and groups is everything the platform did on the broker

    Examples:
      | action                                       |
      | the restore of "shop" completes              |
      | the owner switches "ledger" to the restore   |

  Scenario: a view that counts messages counts an event published again a second time, and the restore says so
    Given "sums" counts the messages on "transactions"
    And "sums" has counted the events 1 to 5 of the entity "a1"
    And "ledger" switched to a restore of "shop" made before the event 1 of the entity "a1" was recorded
    When "ledger" publishes the events 1 to 5 of the entity "a1" again
    Then "sums" counts them again
    And the restore says that a message published again is read again by every view and consumer that reads its topic

  Scenario: a replica promoted with no restore keeps the line of history, and the message ids stay on it
    Given the primary of the project database of "shop" was lost and a replica promoted
    And "ledger" published events before the primary was lost
    When "ledger" publishes an event after the promotion
    Then its message id is on the line of history of the events published before the promotion
