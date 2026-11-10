Feature: An agent's conversation about a data subject is forgotten
  A transcript holds a person's own words, which no field can mark. A session, an agent instance
  or a task may be started with a data subject, and the platform then keeps every message, tool
  call, tool result and result of it encrypted under that data subject's subject key. After an
  erasure the session's conversation reads as erased, a new turn begins with nothing, and an agent
  instance tagged with the data subject is stopped. A session started with no data subject cannot
  be erased, and what a session sent to its model is beyond the installation.

  Background:
    Given a service "support" with a request agent "helper" and an autonomous agent "resolver"

  Scenario: a session started with a data subject keeps its conversation encrypted under the subject key
    Given a session of "helper" started with the data subject "player/8c1f"
    When a turn with the message "my card was declined" is sent to the session
    Then the database of "support" holds the message, the tool calls and the results of the turn encrypted under the subject key of "player/8c1f"
    And nothing in the database of "support" reads as "my card was declined"

  Scenario: a session of an erased data subject reads as erased and a new turn begins with nothing
    Given a session of "helper" started with the data subject "player/8c1f", with turns in it
    And "player/8c1f" has since been erased
    When the session is read
    Then its conversation is erased
    And a new turn in the session begins with no earlier conversation, and the model is told that the earlier conversation was erased

  Scenario: the summary of a session started with a data subject is kept encrypted under the subject key
    Given a session of "helper" started with the data subject "player/8c1f", past the compaction size of "helper"
    When compaction replaces the oldest messages of the session with the summary "the player's card was declined twice"
    Then the database of "support" holds the summary encrypted under the subject key of "player/8c1f"
    And nothing in the database of "support" reads as "the player's card was declined twice"
    And the documentation says that what compaction sent to the model that writes the summary is beyond an erasure

  Scenario: a session of an erased data subject is not compacted
    Given a session of "helper" started with the data subject "player/8c1f", past the compaction size of "helper"
    And "player/8c1f" has since been erased
    When compaction runs on the session
    Then no summary is asked of the model
    And the conversation of the session is erased

  Scenario Outline: an agent instance or a task tagged with an erased data subject reads as erased and the agent instance is stopped
    Given <what> of "resolver" started with the data subject "player/8c1f"
    When "player/8c1f" is erased
    Then its instructions, its task inputs and its results read as erased
    And the agent instance is terminated

    Examples:
      | what              |
      | an agent instance |
      | a task            |

  Scenario: a session started with no data subject is unchanged by any erasure
    Given a session of "helper" started with no data subject, with turns in it
    When "player/8c1f" is erased
    Then the session is unchanged
    And the documentation says that a session started with no data subject cannot be erased
    And the documentation says that what a session sent to its model is beyond an erasure
