Feature: What a topic source reports of what its topic no longer holds
  A broker retains a topic's messages for its retention time and no longer, so a view rebuilt from
  a topic reaches only what the broker still holds. A rebuild is never refused for it. Instead the
  view's topic source reports, for each partition, the retention gap: the beginning offset and the
  earliest retained time, and whether messages are gone. It is carried in the service's metrics, in
  the topology it reports and in the status a member reads, so that nobody reads a log to learn
  what a rebuild reached. A view declared over a topic that keeps less than the installation's
  warning threshold is warned in its status.

  Background:
    Given a service "ledger" with a view "entries" reading the topic "transactions"

  Scenario: a view rebuilt from a topic that no longer holds its earliest messages reports the retention gap for each partition
    Given the broker no longer holds the earliest messages of the topic "transactions"
    When "entries" is rebuilt
    Then the rebuild runs
    And the topic source of "entries" reports, for each partition, the beginning offset and the earliest retained time
    And the retention gap says that messages are gone, because the beginning offset is above 0 or the earliest retained time is later than when the view first read the topic

  Scenario: a view rebuilt from a topic that still holds every message reports no retention gap
    Given the broker holds every message published to the topic "transactions"
    When "entries" is rebuilt
    Then the topic source of "entries" reports no retention gap

  Scenario: a view rebuilt from a compacted topic reports the topic as compacted and not as having a retention gap
    Given the topic "transactions" is compacted
    When "entries" is rebuilt
    Then the topic source of "entries" reports the topic as compacted
    And it reports no retention gap

  Scenario Outline: the retention gap is shown wherever a service's topic sources are
    Given "ledger" is deployed in the project "money" with 2 instances
    And the topic source of "entries" has a retention gap
    When <read>
    Then the topic source "entries" is shown with its retention gap, for each partition, as the instances of "ledger" reported it

    Examples:
      | read                                  |
      | the metrics of "ledger" are read      |
      | the topology of "ledger" is read      |
      | a member reads the status of "ledger" |

  Scenario: a view over a topic that keeps less than the warning threshold is warned in its status
    Given an installation whose warning threshold is "30 days"
    And the topic "transactions" is declared on "money" with the retention time "7 days"
    When a member applies the descriptor for "ledger" in "money"
    Then "ledger" is ready
    And the status of "ledger" carries a retention warning for the view "entries", naming the retention time "7 days" and the warning threshold "30 days"

  Scenario Outline: a topic that keeps everything or is compacted draws no retention warning
    Given an installation whose warning threshold is "30 days"
    And the topic "transactions" is declared on "money" <declared>
    When a member applies the descriptor for "ledger" in "money"
    Then the status of "ledger" carries no retention warning for the view "entries"

    Examples:
      | declared                          |
      | to keep everything                |
      | with the cleanup policy "compact" |
