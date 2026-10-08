Feature: Service secrets kept in Secret Manager
  On the Secret Manager backend a service's secret store keeps each service secret in Secret
  Manager, under a name derived from the project, the service and the secret's name, and the
  service's database holds nothing of it. A service's components keep and read as they do on the
  Postgres backend: every read is of what Secret Manager holds now, so a value kept on one
  instance is read by the next read on every instance, and the rules for a name and a value are
  checked before Secret Manager is called.

  Background:
    Given an installation on the Secret Manager backend
    And a deployed service "payments" in the project "spinvibe"

  Scenario: a service secret kept by one component is read by another, and the database holds nothing of it
    Given a handler of "payments" has kept "sk-acme-1" as the service secret "acme"
    When a handler of another component of "payments" reads the service secret "acme"
    Then the handler is told "sk-acme-1"
    And Secret Manager holds the service secret "acme" of "payments" under a name derived from "spinvibe", "payments" and "acme"
    And the database of "payments" holds no service secret

  Scenario: a service secret kept on one instance is read on another with no restart
    Given two instances of "payments"
    And a handler on one instance has kept "sk-acme-1" as the service secret "acme"
    When a handler on the other instance reads the service secret "acme"
    Then the handler is told "sk-acme-1"
    And no instance of "payments" has restarted

  Scenario: a service secret kept again is read by every instance with no descriptor applied again
    Given two instances of "payments"
    And a handler of "payments" has kept "sk-acme-1" as the service secret "acme"
    When a handler of "payments" keeps "sk-acme-2" as the service secret "acme"
    Then a handler on each instance that reads the service secret "acme" is told "sk-acme-2"
    And the descriptor of "payments" has not been applied again

  Scenario: a service secret that was never kept is read as none, without a failure, on the Secret Manager backend
    When a handler of "payments" reads the service secret "acme"
    Then the handler is told that there is no service secret "acme"
    And the read does not fail

  Scenario: a removed service secret is read as none and no version of it remains
    Given a handler of "payments" has kept "sk-acme-1" as the service secret "acme"
    When a handler of "payments" removes the service secret "acme"
    Then a handler that reads the service secret "acme" is told that there is no service secret "acme"
    And Secret Manager holds no version of the service secret "acme" of "payments"

  Scenario Outline: a name or a value that breaks the rules of the secret store is refused before Secret Manager is called
    When a handler of "payments" keeps <value> as the service secret <name>
    Then the handler is refused
    And the refusal names the rule, as on the Postgres backend
    And Secret Manager was not called

    Examples:
      | name            | value                         |
      | "provider acme" | "sk-acme-1"                   |
      | ""              | "sk-acme-1"                   |
      | "acme"          | ""                            |
      | "acme"          | a value larger than the limit |

  Scenario Outline: a service in every language passes every behaviour of the secret store on the Secret Manager backend
    Given the reference service written in "<language>" on the Secret Manager backend
    When the conformance suite runs against it
    Then every behaviour of the secret store passes, as it does for "Scala"

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
