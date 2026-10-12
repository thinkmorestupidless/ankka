Feature: How long a topic keeps
  A topic's declaration says how long the topic keeps its messages and how much each partition
  keeps, beside its partitions. What a declaration leaves out is filled from the installation's
  defaults when it is accepted and recorded on the declaration, so no topic takes a setting from the
  broker's own defaults, and a later change to the installation's defaults changes no topic already
  declared. The installation sets bounds, and a declaration outside them is refused before anything
  is made on the broker.

  Background:
    Given an installation with a broker
    And the installation's default retention time is "7 days"
    And a project "money"

  Scenario: a topic declared with partitions alone is filled from the installation's defaults
    When a member declares the topic "notices" on "money" with 12 partitions
    Then the declaration of "notices" records the retention time "7 days", the cleanup policy "delete" and the installation's default copies
    And the status of "notices" shows each setting, marked as the installation's default
    And the installation's broker holds each setting on the topic "notices" itself

  Scenario: a topic keeps a message until it is older than its retention time or its partition is larger than its retention size
    When a member declares the topic "transactions" on "money" with the retention time "90 days" and the retention size "50 GiB"
    Then the installation's broker keeps a message on "transactions" until it is older than "90 days" or its partition holds more than "50 GiB", whichever comes first
    And the status of "transactions" shows the retention time "90 days" and the retention size "50 GiB"

  Scenario: a declaration longer than the installation's longest retention time is refused
    Given the installation's longest retention time is "1 year"
    When a member declares the topic "transactions" on "money" with the retention time "2 years"
    Then the member is refused
    And the refusal names the bound
    And nothing is made on the installation's broker

  Scenario: a topic keeps everything where the installation sets no longest retention time
    Given the installation sets no longest retention time
    When a member declares the topic "transactions" on "money" to keep everything
    Then "money" declares the topic "transactions"
    And the status of "transactions" says that it keeps everything

  Scenario: a change to the installation's default changes no topic already declared
    Given the topic "notices" is declared on "money" with partitions alone
    When the installation's default retention time is changed to "14 days"
    Then the installation's broker keeps a message on "notices" for "7 days"
    And the status of "notices" shows the retention time "7 days"

  Scenario: a topic declared before a declaration could say its settings is filled by the control plane when it is upgraded
    Given the topic "notices" was declared on "money" before a declaration could say its settings
    When the control plane starts upgraded
    Then the declaration of "notices" records the retention time "7 days", the cleanup policy "delete", and the installation's default tombstone window, minimum compaction lag and maximum compaction lag
    And "money" records that the platform filled them, and no member
    And the copies of "notices" are what the installation's broker holds
    And the installation's broker holds each setting on the topic "notices" itself
    And the status of "notices" shows each setting, marked as the installation's default
