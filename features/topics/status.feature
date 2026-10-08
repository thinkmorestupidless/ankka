Feature: What a service shows of its topic sources
  A topic source says in the service's log how it subscribed, and the service's metrics list each
  topic source with its group, its start position and its version, so that nobody needs the broker
  to learn which group a view reads under or whether it is behind its recorded version.

  Scenario Outline: a topic source says in the log how it subscribed
    Given a service with a <kind> reading the topic "order-changes"
    When the <kind> subscribes
    Then the service's log names the <kind>, the topic, its group, its start position and its version

    Examples:
      | kind     |
      | view     |
      | consumer |

  Scenario: a service's metrics list each topic source
    Given a service with a view and a consumer each reading a topic
    When the metrics of the service are read
    Then the metrics list each topic source with its topic, its group, its start position and its version

  Scenario: a view behind its recorded version is shown as behind in the metrics
    Given a service with a view declared at version 1 and recorded at version 2
    When the metrics of the service are read
    Then the metrics show the view as behind its recorded version

  Scenario: a service with no topic source lists none in its metrics
    Given a service with no topic source
    When the metrics of the service are read
    Then the metrics list no topic source

  Scenario: a service's status lists each topic source with how far behind it is
    Given a ready service with a consumer reading a topic that holds 100 messages
    And the consumer has handled 40 of them
    When a member reads the status of the service
    Then the status lists the consumer's topic source with its group, its start position, its version and a lag of 60

  Scenario: the console and the server show the same
    Given a ready service with a consumer behind on its topic
    When the service is read in the console and through the server
    Then each shows the topic source with the same lag as the status
