Feature: Changing a topic's settings
  A topic's settings are changed by declaring the topic again. The broker changes them in place,
  and no service is deployed again or restarted for it. The project records who changed which
  setting, from what to what, and when; a declaration that changes nothing records nothing. A
  change that removes messages needs an owner, who is told what it removes before it is sent. A
  topic's copies and minimum in-sync copies are not changed this way: they are fixed when the topic
  is declared.

  Background:
    Given an installation with a broker
    And a project "money"
    And a deployed service "wallet" in "money" that publishes to and reads the topic "transactions"

  Scenario: a topic declared again with a longer retention time is changed on the broker in place
    Given the topic "transactions" is declared on "money" with the retention time "90 days"
    When a member declares the topic "transactions" on "money" with the retention time "180 days"
    Then the installation's broker keeps a message on "transactions" for "180 days"
    And no instance of "wallet" is restarted
    And "money" records the actor, the retention time "90 days" and the retention time "180 days"

  Scenario Outline: a change that removes messages is refused to a member who is not an owner
    Given the topic "transactions" is declared on "money" with <declared>
    When a member who is not an owner declares the topic "transactions" on "money" with <change>
    Then the member is refused
    And the refusal names that only an owner may remove messages

    Examples:
      | declared                     | change                       |
      | the retention time "90 days" | the retention time "30 days" |
      | the retention size "50 GiB"  | the retention size "10 GiB"  |
      | the cleanup policy "compact" | the cleanup policy "delete"  |

  Scenario: an owner is told what a shorter retention time removes and confirms before it is sent
    Given the topic "transactions" is declared on "money" with the retention time "90 days"
    When an owner declares the topic "transactions" on "money" with the retention time "30 days"
    Then the owner is told, before the declaration is sent, that messages older than "30 days" are removed and gone
    And the declaration is sent only once the owner confirms, stating what the owner accepts removing
    And "money" records the owner, the retention time "90 days" and the retention time "30 days"

  Scenario: a declaration that removes messages without stating what it accepts removing is refused
    Given the topic "transactions" is declared on "money" with the retention time "90 days"
    When an owner declares the topic "transactions" on "money" with the retention time "30 days" without stating what it accepts removing
    Then the owner is refused
    And the refusal names that messages older than "30 days" would be removed and gone
    And the installation's broker keeps a message on "transactions" for "90 days"

  Scenario: a change that removes no message needs only a member
    Given the topic "transactions" is declared on "money" with the retention time "90 days"
    When a member who is not an owner declares the topic "transactions" on "money" with the retention time "180 days"
    Then "money" declares the topic "transactions" with the retention time "180 days"

  Scenario: a topic declared again with the cleanup policy "compact" is compacted from then on
    Given the topic "transactions" is declared on "money" with the cleanup policy "delete"
    When a member declares the topic "transactions" on "money" with the cleanup policy "compact"
    Then the topic "transactions" of "money" is compacted on the installation's broker
    And the status of "transactions" shows the cleanup policy "compact"

  Scenario: a running service learns a topic's new cleanup policy from the broker without a restart
    Given the topic "transactions" is declared on "money" with the cleanup policy "delete"
    And a consumer of "wallet" publishes to "transactions" under no key
    When a member declares the topic "transactions" on "money" with the cleanup policy "compact"
    Then publishing under no key fails in "wallet" before anything is sent to the installation's broker
    And no instance of "wallet" is restarted

  Scenario: a declaration that changes nothing records nothing
    Given the topic "transactions" is declared on "money" with the retention time "90 days"
    When a member declares the topic "transactions" on "money" with the retention time "90 days"
    Then "money" records nothing
    And nothing is written to the installation's broker
