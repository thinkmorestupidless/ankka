Feature: A personal field is held only encrypted
  A service marks the fields of an event, a state, a row or a message that are about a person as
  personal fields of one data subject. Every store then holds each one as a personal envelope:
  the data subject readable beside the value encrypted under that data subject's subject key.
  Everything else, such as an amount, an id or a time, is held as it was written, so a ledger
  survives an erasure. The subject key is made on the first write for its data subject, and the
  service reaches the keyring only through its platform and its test kit.

  Background:
    Given a service "players" in the project "brand"
    And the event "PlayerRegistered" of "players" has the fields "email", "name" and "dateOfBirth" marked as personal fields of the data subject "player/8c1f"
    And the event "PlayerRegistered" of "players" has the fields "currency" and "registeredAt" that are not personal fields

  Scenario: the journal holds a personal field encrypted beside its data subject, and every other field as written
    When an entity of "players" records a "PlayerRegistered" with the email "ada@example.com"
    Then the journal of "players" holds the field "email" of that event encrypted under the subject key of "player/8c1f"
    And the journal holds the data subject "player/8c1f" readable beside it
    And the journal holds the fields "currency" and "registeredAt" of that event as they were written
    And nothing in the database of "players" reads as "ada@example.com"

  Scenario: a snapshot holds a personal field encrypted
    Given an entity of "players" whose state has the personal field "email" of "player/8c1f"
    When the entity's snapshot is kept
    Then the snapshot holds the field "email" encrypted under the subject key of "player/8c1f"

  Scenario: a key value entity's state holds a personal field encrypted
    Given a key value entity of "players" whose state has the personal field "email" of "player/8c1f"
    When the key value entity's state is changed
    Then the database of "players" holds the field "email" of that state encrypted under the subject key of "player/8c1f"

  Scenario: a view's row holds a personal field encrypted and is read as the value
    Given a view "profiles" of "players" that reads "PlayerRegistered" into a row with the personal field "email"
    When "profiles" writes the row for "player/8c1f"
    Then the table of "profiles" holds the field "email" of that row encrypted under the subject key of "player/8c1f"
    And a read of the row by its row key is told the email "ada@example.com"
    And a declared query of "profiles" that reads the row is told the email "ada@example.com"

  Scenario: a message on a topic holds a personal field encrypted and a consumer is handed the value
    Given a consumer of "players" that publishes "PlayerRegistered" to the topic "players"
    And a consumer "engagement" in the project "brand" that reads the topic "players"
    When "players" publishes a "PlayerRegistered" with the email "ada@example.com"
    Then the message on the broker holds the field "email" encrypted under the subject key of "player/8c1f"
    And the handler of "engagement" is handed the email "ada@example.com"

  Scenario: a data subject's subject key is made on its first write, once, whichever instance writes first
    Given the keyring holds no subject key for "player/9d2e"
    And two instances of "players"
    When one instance records an event with a personal field of "player/9d2e"
    Then the keyring holds one subject key for "player/9d2e", made with that write
    And an event with a personal field of "player/9d2e" that the other instance records is encrypted under the same subject key

  Scenario Outline: a personal field cannot be written where no service runs
    Given <where>
    When a personal field is written
    Then the write is refused as unavailable
    And the refusal names the keyring
    And nothing is written

    Examples:
      | where                                                      |
      | the routes of "players" listed by the command line         |
      | a test of a component of "players" with no test kit        |
