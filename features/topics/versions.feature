Feature: Rebuilding a topic source by its version
  A view that reads a topic declares a version, a positive whole number that only goes up. A view
  declared at a higher version than the one recorded for it is rebuilt: emptied, and read again from
  its start position under a new group. An instance declaring a lower version stops reading for that
  view. A consumer's version changes its group alone.

  Background:
    Given a topic "order-changes" holding 50 messages

  Scenario: a view at a higher version is rebuilt from every retained message
    Given a view at version 1 that has read every message
    When the service restarts with the view at version 2
    Then the view holds no row written at version 1
    And the view holds 50 rows written at version 2
    And the view reads under a new group naming version 2
    And the group it read under at version 1 is left as it was

  Scenario: a view restarted at the same version is not rebuilt
    Given a view at version 1 that has read every message
    When the service restarts with the view at version 1
    Then no message is delivered to the view again
    And the view holds 50 rows written at version 1

  Scenario: a view rebuilt from the latest message is empty until a message is published
    Given a view at version 1 declaring the start position "latest" that has read every message
    When the service restarts with the view at version 2
    Then the view holds 0 rows

  Scenario: a view with no recorded version is taken to be at version 1
    Given a view with no recorded version that has read every message
    When the service restarts with the view at version 1
    Then no message is delivered to the view again
    And the view holds 50 rows

  Scenario: a consumer at a higher version is delivered every retained message again
    Given a consumer at version 1 declaring the start position "earliest" that has read every message
    When the service restarts with the consumer at version 2
    Then the consumer is delivered 50 messages
    And the consumer reads under a new group naming version 2
    And the group it read under at version 1 is left as it was

  Scenario: a version on a consumer that reads an entity is refused
    When a service registers a consumer reading an entity and declaring the version 2
    Then the registration is refused, naming the consumer

  Scenario Outline: a version that is not a positive whole number is refused
    When a service registers a view reading the topic and declaring the version "<version>"
    Then the registration is refused, naming the view

    Examples:
      | version |
      | 0       |
      | -1      |

  Scenario: during a rolling update the higher version rebuilds the view once and the lower stops writing
    Given an instance running a view at version 1 that has read every message
    When an instance running the view at version 2 starts beside it
    And 10 messages are published to the topic
    Then the view is emptied once
    And the instance at version 1 reads no more of the topic for the view
    And the view holds no row written at version 1

  Scenario: instances starting together at a higher version rebuild the view once
    Given a view at version 1 that has read every message
    When 2 instances start together with the view at version 2
    Then the view is emptied once
    And the view holds 50 rows written at version 2

  Scenario: a service rolled back to a lower version leaves the view as it is
    Given a view at version 2 that has read every message
    When the service restarts with the view at version 1
    Then the view holds 50 rows written at version 2
    And the view reads no more of the topic
    And the service is ready
    And the service's log says the view is behind its recorded version

  Scenario: a rebuild says how far back the broker retains before it removes a row
    Given a view at version 1 that has read every message
    And the earliest retained message was published at "2026-09-01T00:00:00Z"
    When the service restarts with the view at version 2
    Then the service's log names the view, version 1, version 2 and "2026-09-01T00:00:00Z"
    And the log says so before the view is emptied
