Feature: A personal field in every language
  Each SDK has its own personal type and writes the same personal envelope the platform's own
  code does, so that an event with personal fields one language recorded is read by every other.
  The platform's own program beside a process, and the platform that loads a module, carry the
  bytes as they are and never read them.

  Scenario Outline: an SDK writes the same personal envelope and reads it back into its own personal type
    Given a service "players" written in "<language>" whose event "PlayerRegistered" has the field "email" marked as a personal field of the data subject "player/8c1f"
    When an entity of "players" records a "PlayerRegistered" with the email "ada@example.com"
    Then the journal of "players" holds the field "email" as the personal envelope a service written in Scala writes for it
    And the platform's own program recorded the event as bytes without reading the personal envelope
    And the entity recovered in "<language>" reads the field "email" as "ada@example.com" in its own personal type

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
