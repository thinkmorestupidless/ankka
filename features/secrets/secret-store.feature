Feature: A service keeping secrets of its own
  A service keeps a value it must never record, such as a credential a person gave it, as a
  service secret in its secret store. The secret store is in the service's database and apart from
  everything its components know: no event, state, view or timer ever holds a service secret, and
  the database holds one only encrypted with the service's secret key.

  Background:
    Given a service "payments" with a secret key

  Scenario: a service secret kept by one component is read by another
    Given a handler of "payments" has kept "sk-acme-1" as the service secret "acme"
    When a handler of another component of "payments" reads the service secret "acme"
    Then the handler is told "sk-acme-1"

  Scenario: the database holds a service secret only encrypted, and only in the secret store
    When a handler of "payments" keeps "sk-acme-1" as the service secret "acme"
    Then the secret store of "payments" holds the service secret "acme" encrypted
    And no event, state, view or timer of "payments" holds "sk-acme-1"
    And nothing in the database of "payments" reads as "sk-acme-1"

  Scenario: a service secret is still read after the service restarts
    Given a handler of "payments" has kept "sk-acme-1" as the service secret "acme"
    And "payments" has since restarted
    When a handler of "payments" reads the service secret "acme"
    Then the handler is told "sk-acme-1"

  Scenario: a service secret kept on one instance is read on another
    Given two instances of "payments"
    And a handler on one instance has kept "sk-acme-1" as the service secret "acme"
    When a handler on the other instance reads the service secret "acme"
    Then the handler is told "sk-acme-1"

  Scenario: a service secret that was never kept is read as none, without a failure
    When a handler of "payments" reads the service secret "acme"
    Then the handler is told that there is no service secret "acme"
    And the read does not fail

  Scenario: keeping a service secret again replaces its value
    Given a handler of "payments" has kept "sk-acme-1" as the service secret "acme"
    When a handler of "payments" keeps "sk-acme-2" as the service secret "acme"
    Then a handler that reads the service secret "acme" is told "sk-acme-2"
    And the secret store of "payments" holds one service secret "acme"

  Scenario: a removed service secret is read as none
    Given a handler of "payments" has kept "sk-acme-1" as the service secret "acme"
    When a handler of "payments" removes the service secret "acme"
    Then a handler that reads the service secret "acme" is told that there is no service secret "acme"
    And the secret store of "payments" holds no service secret "acme"

  Scenario Outline: an entity or a view is given no secret store
    When <component> of "payments" asks for the secret store
    Then it is given none

    Examples:
      | component |
      | an entity |
      | a view    |

  Scenario: a workflow reads a service secret in a step and not in a command
    Given a handler of "payments" has kept "sk-acme-1" as the service secret "acme"
    When a command of a workflow of "payments" reads the service secret "acme"
    Then the command is refused
    And the refusal says that a service secret is read in a step

  Scenario: a service whose secret key is malformed does not start
    Given a service "ledger" whose secret key is "not-a-key"
    When "ledger" starts
    Then "ledger" does not start
    And the reason names the variable "ANKKA_SECRET_KEY"

  Scenario: a service with no secret key starts
    Given a service "ledger" with no secret key
    When "ledger" starts
    Then "ledger" is ready

  Scenario Outline: a service with no secret key cannot keep or read a service secret
    Given a service "ledger" with no secret key
    When a handler of "ledger" <does>
    Then what the handler did fails
    And the failure names the variable "ANKKA_SECRET_KEY"

    Examples:
      | does                                           |
      | keeps "sk-acme-1" as the service secret "acme" |
      | reads the service secret "acme"                |

  Scenario: a service secret cannot be read with another secret key
    Given a handler of "payments" has kept "sk-acme-1" as the service secret "acme"
    And the secret key of "payments" has since been changed
    When a handler of "payments" reads the service secret "acme"
    Then the read fails
    And the failure says that the secret key is not the one the service secret was kept with
    And the handler is not told that there is no service secret "acme"

  Scenario: a value larger than a service secret may be is refused
    When a handler of "payments" keeps a value larger than the limit as the service secret "acme"
    Then the handler is refused
    And the refusal names the limit
    And the secret store of "payments" holds no service secret "acme"

  Scenario: an empty value is refused
    When a handler of "payments" keeps "" as the service secret "acme"
    Then the handler is refused
    And the secret store of "payments" holds no service secret "acme"

  Scenario: a service secret's name may have a slash
    Given a handler of "payments" has kept "sk-acme-1" as the service secret "provider/acme"
    When a handler of "payments" reads the service secret "provider/acme"
    Then the handler is told "sk-acme-1"

  Scenario Outline: a service secret's name is refused when it breaks the rule for names
    When a handler of "payments" keeps "sk-acme-1" as the service secret "<name>"
    Then the handler is refused
    And the refusal names the rule for names

    Examples:
      | name          |
      | provider acme |
      | provider:acme |
      |               |

  Scenario: a service secret's name longer than the limit is refused
    When a handler of "payments" keeps "sk-acme-1" as a service secret with a name longer than the limit
    Then the handler is refused
    And the refusal names the rule for names
