Feature: Publishing several messages for one change
  A consumer's handler may publish several messages for one change, each under a key of its own.
  They are published to the consumer's topic in the order the handler gave them, and the change
  counts as handled only once the broker has accepted every one.

  Background:
    Given a consumer of the event sourced entity "cart" that publishes to the topic "cart-lines"

  Scenario: a change's messages are published in the order the handler gave them
    Given the consumer's handler publishes three messages for each change
    When a change of the entity "c1" is handled
    Then the topic holds three messages for the change, in the order the handler gave them
    And each is published as a single message for a change is

  Scenario Outline: a message is published under the key it names, else under its entity's id
    Given the consumer's handler publishes a message for each change, naming <named>
    When a change of the entity "c1" is handled
    Then the message's key is "<key>"
    And the message is about the entity "c1"

    Examples:
      | named               | key    |
      | the key "line-2"    | line-2 |
      | no key              | c1     |

  Scenario: a change whose messages the broker does not all accept is delivered again
    Given the consumer's handler publishes three messages for each change
    And the broker refuses the second of the three messages once
    When a change of the entity "c1" is handled
    Then the change is not recorded as handled
    And the change is delivered again
    And the topic holds all three messages for the change, the first perhaps twice

  Scenario: a change for which the handler publishes no message is handled
    Given the consumer's handler publishes no message for each change
    When a change of the entity "c1" is handled
    Then nothing is published to the topic
    And the change is recorded as handled

  Scenario: a consumer that may publish several messages and has no topic is refused
    When a service registers a consumer whose handler may publish several messages and that names no topic
    Then the registration is refused, naming the consumer

  Scenario: a consumer that publishes one message for each change publishes what it always has
    Given a consumer built on an earlier release that publishes one message for each change
    When it runs on the current release
    Then each message it publishes has the same value, key and attributes as on the earlier release
