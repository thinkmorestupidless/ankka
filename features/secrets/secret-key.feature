Feature: The secret key of a deployed service
  The platform makes each deployed service a secret key of its own unless its descriptor gives
  one, and keeps it when the service is deleted. The secret key is a platform setting: only the
  platform's own program holds it, never the developer's.

  Scenario: a deployed service is given a secret key of its own
    Given a descriptor for a service "payments" that gives no secret key
    When a member applies the descriptor
    Then "payments" is deployed with a secret key the platform made for it

  Scenario: a secret key the descriptor gives is the one the service has
    Given a descriptor for a service "payments" that gives the variable "ANKKA_SECRET_KEY"
    When a member applies the descriptor
    Then "payments" is deployed with the secret key the descriptor gave
    And the platform makes no secret key for "payments"

  Scenario: a service deleted and deployed again reads the service secrets it kept before
    Given a deployed service "payments" that has kept "sk-acme-1" as the service secret "acme"
    And "payments" has since been deleted
    When a member applies the descriptor for "payments" again
    Then a handler of "payments" that reads the service secret "acme" is told "sk-acme-1"

  Scenario Outline: the process of a deployed service is not given the secret key
    Given a deployed service "payments" written in "<language>"
    When the environment of the process of "payments" is read
    Then it has no variable "ANKKA_SECRET_KEY"

    Examples:
      | language   |
      | Python     |
      | TypeScript |

  Scenario: a module is not shown the secret key
    Given a service "payments" written in "Rust" with a secret key
    When "payments" asks for the variable "ANKKA_SECRET_KEY"
    Then "payments" is told that the variable is not set
