Feature: A topic on a declared broker
  A member declares a broker on a project, with its address and the project secret holding its
  credential, and a component names that broker for a topic it reads or publishes to. The service
  keeps the installation's broker for every other topic. The credential reaches only the
  platform's container.

  Background:
    Given an installation with a broker
    And a project "shop"
    And a broker outside the installation holding the topic "events"

  Scenario Outline: a broker is declared on a project with its credential in a project secret
    Given the project secret "legacy-credential" on "shop" holds <credential> for the outside broker
    When a member declares the broker "legacy" on "shop" with the address of the outside broker, the shape <shape> and the project secret "legacy-credential"
    Then the brokers of "shop" show "legacy" with the shape <shape>

    Examples:
      | shape          | credential                                     |
      | "certificate"  | a client certificate, its key and the authority |
      | "SASL"         | a username, a password and the authority       |

  Scenario: a declaration whose secret lacks what its shape needs is refused
    Given the project secret "legacy-credential" on "shop" holds a username and a password and no authority
    When a member declares the broker "legacy" on "shop" with the address of the outside broker, the shape "SASL" and the project secret "legacy-credential"
    Then the declaration is refused, naming what the secret lacks

  Scenario: a topic source names a declared broker and reads from it
    Given the broker "legacy" is declared on "shop"
    And a ready service "intake" whose consumer reads "events" from the broker "legacy"
    When a message is produced to "events" on the outside broker
    Then the consumer of "intake" is handed the message

  Scenario: a consumer reads from a declared broker and publishes to the installation's
    Given the broker "legacy" is declared on "shop"
    And the topic "orders" is declared on "shop"
    And a ready service "intake" whose consumer reads "events" from the broker "legacy" and publishes to "orders"
    When a message is produced to "events" on the outside broker
    Then a message is read from the topic "orders" of "shop" on the installation's broker

  Scenario: a component naming a broker the project has not declared is refused
    Given a service "intake" whose consumer reads "events" from the broker "legacy"
    When a member applies the descriptor for "intake"
    Then the consumer neither reads nor publishes
    And the status of "intake" says the broker "legacy" is not declared on "shop"

  Scenario: a declared broker's credential never reaches the process
    Given the broker "legacy" is declared on "shop"
    And a ready service "intake", hosted as a process, whose consumer reads "events" from the broker "legacy"
    When the process's environment and mounts are read
    Then neither holds the credential of the outside broker
