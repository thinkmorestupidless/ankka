Feature: A service reaches only its own secrets
  On the Secret Manager backend a service reaches Secret Manager as its own identity: the one the
  platform gives its instances, with no credential for Google Cloud in its environment, its
  descriptor or its secrets. The cloud provider writes the secret access that admits that identity to its
  own service secrets and to a read of its project's entries, and to nothing else; Google Cloud
  refuses the rest, not code in the instance. Secret access follows the service's name, so a service
  deleted and deployed again reads what it kept.

  Background:
    Given an installation on the Secret Manager backend

  Scenario: a service is refused another service's service secret of the same project
    Given deployed services "wallet" and "payments" in the project "spinvibe"
    And a handler of "payments" has kept "sk-acme-1" as the service secret "acme"
    When "wallet" reads the service secret "acme" of "payments" from Secret Manager as its own identity
    Then Google Cloud refuses "wallet"

  Scenario Outline: a service is refused the secrets of another project
    Given a deployed service "wallet" in the project "spinvibe"
    And a deployed service "ledger" in the project "bank" that has kept "sk-acme-1" as the service secret "acme"
    And the entry "STRIPE_KEY" of the project secret "checkout" of "bank" is set to "sk_live_1"
    When "wallet" reads <what> from Secret Manager as its own identity
    Then Google Cloud refuses "wallet"

    Examples:
      | what                                                              |
      | the service secret "acme" of "ledger"                             |
      | the entry "STRIPE_KEY" of the project secret "checkout" of "bank" |

  Scenario: a service reads an entry of a project secret of its own project
    Given a deployed service "wallet" in the project "spinvibe"
    And the entry "STRIPE_KEY" of the project secret "checkout" of "spinvibe" is set to "sk_live_1"
    When "wallet" reads the entry "STRIPE_KEY" of the project secret "checkout" from Secret Manager as its own identity
    Then "wallet" is admitted

  Scenario: a service is refused a write to an entry of a project secret of its own project
    Given a deployed service "wallet" in the project "spinvibe"
    And the entry "STRIPE_KEY" of the project secret "checkout" of "spinvibe" is set to "sk_live_1"
    When "wallet" sets the entry "STRIPE_KEY" of the project secret "checkout" in Secret Manager as its own identity
    Then Google Cloud refuses "wallet"

  Scenario: a service cannot list the secrets Google Cloud holds for the installation
    Given a deployed service "wallet" in the project "spinvibe"
    And a deployed service "payments" in the project "spinvibe" that has kept "sk-acme-1" as the service secret "acme"
    When "wallet" lists every secret the installation has in Secret Manager as its own identity
    Then Google Cloud refuses "wallet"
    And "wallet" is shown no name of a service secret of "payments"

  Scenario: a service that creates a secret under another service's name can neither read nor write it, and the other service keeps over it
    Given deployed services "wallet" and "payments" in the project "spinvibe"
    When "wallet" creates in Secret Manager, as its own identity, the secret derived for the service secret "acme" of "payments"
    Then "wallet" is admitted
    And Google Cloud refuses "wallet" a read of it, a version added to it and its removal
    And a handler of "payments" that keeps "sk-acme-1" as the service secret "acme" is not refused
    And a handler of "payments" that reads the service secret "acme" is told "sk-acme-1"

  Scenario: a service deleted and deployed again reads the service secrets it kept in Secret Manager
    Given a deployed service "payments" that has kept "sk-acme-1" as the service secret "acme"
    And "payments" has since been deleted
    When a member applies the descriptor for "payments" again
    Then a handler of "payments" that reads the service secret "acme" is told "sk-acme-1"

  Scenario: an instance of a service holds no credential for Google Cloud and does not read its secret key
    Given a deployed service "payments"
    When everything an instance of "payments" holds is read
    Then it holds no credential for Google Cloud
    And the secret key it holds is not read
