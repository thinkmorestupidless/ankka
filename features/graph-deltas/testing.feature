Feature: Testing what a consumer publishes
  A developer tests a consumer that publishes several messages, or deltas, as any consumer is
  tested: a test hands it a change and reads back what it published, as messages and keys or as
  elements, with no broker and no service started.

  Scenario Outline: a test reads back the elements a consumer published for a change
    Given a consumer written in "<language>" that publishes deltas
    And a change whose sequence number is 7
    When a test hands the consumer the change, with no broker and no service started
    Then the test reads back each element published: whether it is a node or a relationship, its element id, its version, its labels or type, the nodes it runs between and its properties

    Examples:
      | language   |
      | scala      |
      | python     |
      | typescript |
      | rust       |

  Scenario Outline: a test reads back every message a consumer published and the key each named
    Given a consumer written in "<language>" whose handler publishes several messages for a change
    When a test hands the consumer the change, with no broker and no service started
    Then the test reads back every message published and the key each named

    Examples:
      | language   |
      | scala      |
      | python     |
      | typescript |
      | rust       |

  Scenario: a test of a whole service reads each published delta with its key
    Given a service with a consumer that publishes deltas
    When the service runs in the test kit and a change is handled
    Then the test reads each message published to the topic with its key
    And the test reads each message back as a delta
