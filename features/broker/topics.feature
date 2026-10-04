Feature: Topics the platform provides
  A descriptor declares the topics its service publishes to, and the platform makes each on the
  installation's broker for the service's project. The service is told where the broker is and
  proves which service it is with the certificate it already has. Its components go on naming a
  topic by the name the descriptor declared; the broker holds it under a name that carries the
  project.

  Background:
    Given an installation with a broker
    And a project "money"

  Scenario: a declared topic is made on the installation's broker for its project
    Given a descriptor for a service "wallet" in "money" that declares the topic "transactions" with 12 partitions
    And the descriptor gives no broker variable
    When a member applies the descriptor
    Then the installation's broker has the topic "transactions" of "money" with 12 partitions
    And the broker holds that topic under the name "money.transactions"

  Scenario: a service with a declared topic is told where the installation's broker is
    Given a deployed service "wallet" in "money" that declares the topic "transactions"
    When the environment of "wallet" is read
    Then it has the variable "ANKKA_KAFKA_BOOTSTRAP_SERVERS", naming the installation's broker

  Scenario: a service proves which service it is to the broker with its certificate
    Given a deployed service "wallet" in "money" that declares the topic "transactions"
    When "wallet" connects to the installation's broker
    Then the broker is told that it is "wallet" of "money" by the certificate of "wallet"
    And the platform keeps no other credential for "wallet" on the broker

  Scenario: both programs of a service hosted as a process are told where the installation's broker is
    Given a deployed service "wallet" in "money" hosted as a process that declares the topic "transactions"
    When the environment of "wallet" is read
    Then the platform's program of "wallet" is given the variable "ANKKA_KAFKA_BOOTSTRAP_SERVERS"
    And the process of "wallet" is given the variable "ANKKA_KAFKA_BOOTSTRAP_SERVERS"

  Scenario: a consumer publishes to its project's topic by the name the descriptor declared
    Given a deployed service "wallet" in "money" that declares the topic "transactions"
    And a consumer "notifier" of "wallet" that publishes to the topic "transactions"
    When "notifier" handles an event
    Then what "notifier" published is read from "money.transactions" on the installation's broker

  Scenario: another service of the project reads a declared topic without declaring it
    Given a deployed service "wallet" in "money" that declares the topic "transactions"
    And a deployed service "ledger" in "money" that declares no topic, with a view "entries" that reads the topic "transactions"
    When a consumer of "wallet" publishes to the topic "transactions"
    Then the view "entries" shows what was published

  Scenario Outline: the status says how far the platform has got with a service's topics
    Given a deployed service "wallet" in "money" that declares the topic "transactions"
    And the installation's broker <state>
    When a member reads the status of "wallet"
    Then the status says that the broker of "wallet" is "<word>"

    Examples:
      | state                                            | word    |
      | has not yet made the topic                       | Waiting |
      | has made the topic with fewer partitions so far  | Waiting |
      | has made the topic and the credential            | Ready   |
      | has a problem with the topic that will not clear | Failed  |
