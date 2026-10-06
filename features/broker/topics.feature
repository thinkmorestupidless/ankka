Feature: Using a project's topics
  Every service of a project with components is told where the installation's broker is, and proves
  which service it is with the certificate it already has. Its components name a topic by the name
  the project declared; the broker holds it under a name that carries the project. A component that
  names a topic its project has not declared waits for it, and the service's status says so.

  Background:
    Given an installation with a broker
    And a project "money"

  Scenario: a service is told where the installation's broker is
    Given a deployed service "wallet" in "money"
    When the environment of "wallet" is read
    Then it has the variable "ANKKA_KAFKA_BOOTSTRAP_SERVERS", naming the installation's broker

  Scenario: a service proves which service it is to the broker with its certificate
    Given a deployed service "wallet" in "money"
    When "wallet" connects to the installation's broker
    Then the broker is told that it is "wallet" of "money" by the certificate of "wallet"
    And the platform keeps no other credential for "wallet" on the broker

  Scenario: both programs of a service hosted as a process are told where the installation's broker is
    Given a deployed service "wallet" in "money" hosted as a process
    When the environment of "wallet" is read
    Then the platform's program of "wallet" is given the variable "ANKKA_KAFKA_BOOTSTRAP_SERVERS"
    And the process of "wallet" is given the variable "ANKKA_KAFKA_BOOTSTRAP_SERVERS"

  Scenario: a consumer publishes to its project's topic by the name the project declared
    Given the topic "transactions" is declared on "money"
    And a deployed service "wallet" in "money"
    And a consumer "notifier" of "wallet" that publishes to the topic "transactions"
    When "notifier" handles an event
    Then what "notifier" published is read from "money.transactions" on the installation's broker

  Scenario: every service of the project reads a declared topic
    Given the topic "transactions" is declared on "money"
    And a deployed service "wallet" in "money"
    And a deployed service "ledger" in "money", with a view "entries" that reads the topic "transactions"
    When a consumer of "wallet" publishes to the topic "transactions"
    Then the view "entries" shows what was published

  Scenario: a consumer that publishes to a topic its project has not declared waits for it
    Given a deployed service "ledger" in "money"
    And a consumer "notifier" of "ledger" that publishes to the topic "entries"
    When "notifier" handles an event
    Then the installation's broker has no topic "entries" of "money"
    And the logs of "ledger" name the topic "entries"
    And "ledger" is ready

  Scenario: what waited for a topic is published once the topic is declared
    Given a deployed service "ledger" in "money"
    And a consumer "notifier" of "ledger" that has handled an event and waits to publish to the topic "entries"
    When a member declares the topic "entries" on "money"
    Then what "notifier" waited to publish is read from "money.entries" on the installation's broker

  Scenario: the status of a service names a topic it uses that its project has not declared
    Given a deployed service "ledger" in "money"
    And a consumer "notifier" of "ledger" that publishes to the topic "entries"
    When a member reads the status of "ledger"
    Then the status says that "ledger" uses the topic "entries", which "money" has not declared

  Scenario: a topic is no longer named as undeclared once its project declares it
    Given a deployed service "ledger" in "money"
    And a consumer "notifier" of "ledger" that publishes to the topic "entries"
    When a member declares the topic "entries" on "money"
    Then the status of "ledger" names no topic its project has not declared

  Scenario: a web-hosted service is given nothing of the installation's broker
    Given a descriptor for the web-hosted service "web" in "money"
    When a member applies the descriptor
    Then the environment of "web" has no broker variable
    And the installation's broker has no credential for "web"

  Scenario Outline: the status says how far the platform has got with a service's credential
    Given a deployed service "wallet" in "money"
    And the installation's broker <state>
    When a member reads the status of "wallet"
    Then the status says that the broker of "wallet" is "<word>"

    Examples:
      | state                            | word        |
      | has not yet made the credential  | Waiting     |
      | has made the credential          | Provisioned |
