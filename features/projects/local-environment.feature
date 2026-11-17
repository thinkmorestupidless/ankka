Feature: A service's environment on a developer's machine
  Bringing a project up starts each service with the environment its descriptor declares. The
  project's local values say what a variable is on a developer's machine where the descriptor
  cannot: one read from a secret, or an address that exists only on an installation. The command
  line itself sets where the service's database, the broker and the other services are.

  Background:
    Given a project "shop" with the services "orders" and "billing"

  Scenario: a service is started with the literal environment its descriptor declares
    Given the descriptor of "orders" declares the variable "CURRENCY" as "EUR"
    When the developer brings "shop" up
    Then "orders" is running with "CURRENCY" set to "EUR"

  Scenario: a variable the descriptor reads from a secret takes the value the project's local values give it
    Given the descriptor of "orders" reads the variable "PAYMENT_KEY" from a secret
    And the local values of "shop" give "orders" the variable "PAYMENT_KEY" as "test-key"
    When the developer brings "shop" up
    Then "orders" is running with "PAYMENT_KEY" set to "test-key"

  Scenario: a variable read from a secret with no local value is refused before anything starts, naming the service and the variable
    Given the descriptor of "orders" reads the variable "PAYMENT_KEY" from a secret
    And the local values of "shop" give "orders" no variable "PAYMENT_KEY"
    When the developer brings "shop" up
    Then the command line refuses, naming "orders" and "PAYMENT_KEY"
    And nothing is started

  Scenario: a local value replaces a literal the descriptor declares
    Given the descriptor of "orders" declares the variable "CURRENCY" as "EUR"
    And the local values of "shop" give "orders" the variable "CURRENCY" as "GBP"
    When the developer brings "shop" up
    Then "orders" is running with "CURRENCY" set to "GBP"

  Scenario: a variable the platform sets is not taken from the descriptor or the local values
    Given the local values of "shop" give "orders" the variable "ANKKA_DB_NAME" as "other"
    When the developer brings "shop" up
    Then the command line refuses, naming "orders" and "ANKKA_DB_NAME"
    And nothing is started

  Scenario: the project's local values are never printed
    Given the local values of "shop" give "orders" the variable "PAYMENT_KEY" as "test-key"
    When the developer brings "shop" up and asks what of "shop" is running
    Then the command line does not show "test-key"
