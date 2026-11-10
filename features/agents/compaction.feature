Feature: A long session is compacted
  A developer turns compaction on for an agent and sets its compaction size. After a turn that
  leaves a session past that size, the platform replaces the session's oldest messages with a
  summary and keeps its most recent ones verbatim, so the model is sent less on the next turn. A
  session under the size, or with too few messages beyond those kept, is left as it is. A session
  started with a data subject is compacted as any other.

  Background:
    Given a service "support" with an agent "helper"
    And compaction of "helper" with a compaction size of "100000" characters that keeps the "10" most recent messages and replaces no fewer than "4"

  Scenario: a session past the compaction size has its oldest messages replaced by a summary and its most recent kept verbatim
    Given a session of "helper" started with no data subject, with "14" messages of "100103" characters together
    When a turn in the session ends
    Then the session holds a summary and then its "10" most recent messages verbatim

  Scenario: a session started with a data subject past the compaction size is compacted as one started with none
    Given a session of "helper" started with the data subject "player/8c1f", with "14" messages of "100103" characters together
    When a turn in the session ends
    Then the session holds a summary and then its "10" most recent messages verbatim

  Scenario: a session under the compaction size is not compacted
    Given a session of "helper" started with the data subject "player/8c1f", with "14" messages of "2000" characters together
    When a turn in the session ends
    Then the session holds its "14" messages verbatim and no summary

  Scenario: a session with too few messages beyond those kept is not compacted
    Given a session of "helper" started with the data subject "player/8c1f", with "12" messages of "100103" characters together
    When a turn in the session ends
    Then the session holds its "12" messages verbatim and no summary

  Scenario: a compaction that fails is reported in the log of the service by its session and never by its conversation
    Given a session of "helper" started with the data subject "player/8c1f", with "14" messages of "100103" characters together
    And the model that writes the summary fails
    When a turn in the session ends
    Then the log of "support" holds a warning that names the session and the failure
    And nothing in the log of "support" reads as a message of the session
    And the session holds its "14" messages verbatim and no summary
