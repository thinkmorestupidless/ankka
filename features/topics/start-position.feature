Feature: Where a topic source starts
  A view or consumer that reads a topic declares its start position: the earliest retained message,
  the latest, or a time. The start position applies the first time its group reads the topic; after
  that it resumes where it stopped.

  Background:
    Given a topic "order-changes" holding 50 messages

  Scenario: a view starting at the earliest message holds every retained message
    When a view declaring the start position "earliest" subscribes for the first time
    Then the view holds 50 rows

  Scenario: a view starting at the latest message holds only what is published after it starts
    Given a view declaring the start position "latest" that has subscribed for the first time
    When 10 messages are published to the topic
    Then the view holds 10 rows

  Scenario: a view starting at a time holds every message published since that time
    Given the last 30 messages were published after "2026-10-01T12:00:00Z"
    When a view declaring the start position "2026-10-01T12:00:00Z" subscribes for the first time
    Then the view holds 30 rows

  Scenario: a view starting at a time goes on to read what is published after it starts
    Given the last 30 messages were published after "2026-10-01T12:00:00Z"
    And a view declaring the start position "2026-10-01T12:00:00Z" that has subscribed for the first time
    When 10 messages are published to the topic
    Then the view holds 40 rows

  Scenario: a view declaring no start position starts at the earliest message
    When a view declaring no start position subscribes for the first time
    Then the view holds 50 rows

  Scenario: a restarted view reads what was published while it was stopped
    Given a view declaring the start position "latest" that has read every message and stopped
    And 10 messages are published to the topic
    When the view is restarted
    Then the view holds 60 rows

  Scenario: a restarted view is not delivered what it has already read
    Given a view declaring the start position "earliest" that has read every message and stopped
    When the view is restarted
    Then no message is delivered to the view again

  Scenario Outline: a consumer reading a topic must declare its start position
    When a service written in "<language>" registers a consumer reading the topic and declaring no start position
    Then the registration is refused, naming the consumer and the missing start position

    Examples:
      | language   |
      | scala      |
      | python     |
      | typescript |
      | rust       |

  Scenario: a consumer whose service cannot declare a start position starts at the earliest message
    Given a service built on a release of ankka that cannot declare a start position
    And the service has a consumer reading the topic
    When the consumer subscribes for the first time
    Then the consumer is delivered 50 messages
    And the service's log says the consumer declares no start position

  Scenario Outline: a start position is honoured in every language a service is written in
    Given a service written in "<language>" with a view declaring the start position "latest" that has subscribed for the first time
    When 10 messages are published to the topic
    Then the view holds 10 rows

    Examples:
      | language   |
      | scala      |
      | python     |
      | typescript |
      | rust       |

  Scenario: a start position earlier than every retained message starts at the earliest
    When a view declaring a start position earlier than every retained message subscribes for the first time
    Then the view holds 50 rows

  Scenario: a start position later than now starts at the latest message
    Given a view declaring a start position later than now that has subscribed for the first time
    When 10 messages are published to the topic
    Then the view holds 10 rows
